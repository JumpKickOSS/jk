// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Runtime classpath for a thin jar from its Maven POM and the local repo layout.
 *
 * <p>Walks the <em>effective</em> POM ({@link EffectivePomBuilder}: parent-chain properties, BOM
 * imports, {@code dependencyManagement} version/scope defaults) and follows compile/runtime
 * dependencies (transitives included; {@code provided} / {@code test} / {@code optional}
 * transitives skipped). Artifacts are taken from {@code repos/jk-local}, {@code repos/jumpkick}, then
 * {@code repos/central}, fetching a miss from the HTTP remotes when the session is online.
 */
public final class PomRuntimeClasspath {

    private static final List<String> REPOS =
            List.of(RepoArtifactResolver.JK_LOCAL, RepositorySpec.JUMPKICK.name(), RepositorySpec.CENTRAL);
    private static final Pattern VERSION = Pattern.compile("\\d+(?:[._-][A-Za-z0-9]+)*");

    /**
     * Product store the host engine uses. Sandboxed test JVMs ({@code JK_HOME} under {@code
     * target/test-jk-home}) cannot see Zinc there; the host passes this so a workspace-built worker
     * still resolves its POM closure.
     */
    public static final String HOST_STORE_PROPERTY = "jk.host.store";

    private PomRuntimeClasspath() {}

    /**
     * Process-lifetime memo of {@link #resolve(Path)}: every fork re-resolved the full effective
     * POM walk (worker POM + every transitive POM, XML parses, possible network) in the resident
     * engine. Keyed on the jar and POM identity (path, size, mtime) plus the repo-group identity;
     * a hit is re-validated with one stat per entry so a swept store falls back to a real
     * resolve. Only successes are cached — a store that gains the missing artifact later must be
     * able to succeed.
     */
    private static final ConcurrentHashMap<String, List<Path>> RESOLVE_CACHE = new ConcurrentHashMap<>();

    private static final int RESOLVE_CACHE_MAX = 256;

    public static void clearResolveCacheForTests() {
        RESOLVE_CACHE.clear();
        STORE_REPOS.clear();
    }

    /**
     * Worker jar plus located compile/runtime jars. The POM is the sibling of the jar, the
     * installed POM for the jar's Maven coordinate in the artifact store, or — for a workspace
     * {@code target/} worker — the host product store ({@link #HOST_STORE_PROPERTY} / unsandboxed
     * default).
     *
     * @throws IllegalStateException if no POM exists or a declared runtime dep is missing
     */
    public static List<Path> resolve(Path workerJar) {
        if (workerJar == null || !Files.isRegularFile(workerJar)) {
            throw new IllegalStateException("worker jar is missing: " + workerJar);
        }
        Path worker = workerJar.toAbsolutePath().normalize();
        Path pom = pomFor(worker);
        Path extra = extraStoreFor(worker);
        String key = pom == null ? null : resolveCacheKey(worker, pom, extra);
        if (key != null) {
            List<Path> hit = RESOLVE_CACHE.get(key);
            if (hit != null) {
                boolean intact = true;
                for (Path p : hit) {
                    if (!Files.isRegularFile(p)) {
                        intact = false;
                        break;
                    }
                }
                if (intact) return hit;
                RESOLVE_CACHE.remove(key);
            }
        }
        List<Path> resolved = List.copyOf(resolve(worker, reposFor(worker)));
        if (key != null && RESOLVE_CACHE.size() < RESOLVE_CACHE_MAX) {
            RESOLVE_CACHE.put(key, resolved);
        }
        return resolved;
    }

    private static @Nullable String resolveCacheKey(Path worker, Path pom, @Nullable Path extra) {
        try {
            return worker + "|" + Files.size(worker) + "|"
                    + Files.getLastModifiedTime(worker).toMillis()
                    + "|" + pom + "|" + Files.size(pom) + "|"
                    + Files.getLastModifiedTime(pom).toMillis()
                    + "|" + RepositorySpec.officialUrl()
                    + "|" + (extra == null ? "" : extra);
        } catch (IOException e) {
            return null; // unstatable — resolve uncached and let the real walk surface the error
        }
    }

    /**
     * As {@link #resolve(Path)} using {@code repos} for parent/BOM/artifact lookup. Tests pass a
     * file-only {@link RepoGroup} so the walk never touches the network.
     */
    public static List<Path> resolve(Path workerJar, RepoGroup repos) {
        if (workerJar == null || !Files.isRegularFile(workerJar)) {
            throw new IllegalStateException("worker jar is missing: " + workerJar);
        }
        Path worker = workerJar.toAbsolutePath().normalize();
        Path pom = pomFor(worker);
        if (pom == null) {
            throw new IllegalStateException(
                    "worker " + worker + " has no Maven POM; run `jk install` to publish jar+pom to repos/jk-local");
        }
        List<Path> out = new ArrayList<>();
        out.add(worker);
        try {
            EffectivePomBuilder builder = new EffectivePomBuilder(repos);
            Pom raw = PomParser.parse(Files.readAllBytes(pom));
            EffectivePom effective = builder.build(raw);
            walk(effective, rootPins(raw, effective), coordinateOf(worker), builder, repos, out);
        } catch (IllegalStateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("failed reading worker POM " + pom + ": " + e.getMessage(), e);
        } catch (RuntimeException | IOException e) {
            throw new IllegalStateException("failed reading worker POM " + pom + ": " + e.getMessage(), e);
        }
        return out;
    }

    /**
     * Fetch the runtime closure of {@code root} (jar + POM per compile/runtime dependency, parents
     * and BOM imports included) into {@code repos}. Official plugin install uses this so a thin
     * published POM is enough to materialize the worker classpath.
     */
    public static void fetchRuntimeClosure(Coordinate root, RepoGroup repos) throws IOException, InterruptedException {
        EffectivePomBuilder builder = new EffectivePomBuilder(repos);
        EffectivePom pom = builder.build(root);
        Pom raw = repos.tryFetchPom(root)
                .map(hit -> {
                    try {
                        return PomParser.parse(Files.readAllBytes(hit.fetched().cachePath()));
                    } catch (IOException e) {
                        throw new IllegalStateException("failed reading worker POM " + root + ": " + e.getMessage(), e);
                    }
                })
                .orElseThrow(() -> new IllegalStateException("worker POM " + root + " was not found in any repo"));
        walk(pom, rootPins(raw, pom), root, builder, repos, new ArrayList<>());
    }

    /**
     * One {@link RepoGroup} per (store root, official URL): each build allocated a fresh
     * {@link Http} whose {@code java.net.http.HttpClient} (selector thread + buffers) was never
     * closed — a leak per fork in the heap-disciplined engine. RepoGroups are immutable, so
     * sharing is safe; the URL is in the key because tests repoint the official override.
     */
    private static final ConcurrentHashMap<String, RepoGroup> STORE_REPOS = new ConcurrentHashMap<>();

    private static final int STORE_REPOS_MAX = 32;

    /**
     * {@code repos/jk-local} plus JumpKick and Central HTTP remotes, CAS-rooted at {@code storeRoot}.
     * Local is a priority repo so {@code installLocal} artifacts outrank exclusive remote bindings.
     */
    static RepoGroup storeRepos(Path storeRoot) {
        return storeRepos(storeRoot, null);
    }

    private static RepoGroup reposFor(Path worker) {
        return storeRepos(storeRootOf(worker), extraStoreFor(worker));
    }

    static RepoGroup storeRepos(Path storeRoot, @Nullable Path extraStore) {
        Path extra = extraStore == null ? null : extraStore.toAbsolutePath().normalize();
        String key = storeRoot.toAbsolutePath().normalize()
                + "|"
                + RepositorySpec.officialUrl()
                + "|"
                + (extra == null ? "" : extra);
        RepoGroup cached = STORE_REPOS.get(key);
        if (cached != null) return cached;
        RepoGroup built = buildStoreRepos(storeRoot, extra);
        if (STORE_REPOS.size() < STORE_REPOS_MAX) {
            STORE_REPOS.putIfAbsent(key, built);
        }
        return built;
    }

    private static RepoGroup buildStoreRepos(Path storeRoot, @Nullable Path extraStore) {
        Cas cas = new Cas(storeRoot);
        Http http = new Http();
        MavenRepo local = storeOnlyRepo(
                RepoArtifactResolver.JK_LOCAL,
                storeRoot
                        .resolve("repos")
                        .resolve(RepoArtifactResolver.JK_LOCAL)
                        .toUri(),
                http,
                cas);
        // Launch-time resolution is overwhelmingly store-resident, but for unclaimed groups the
        // jumpkick specialist's warm mirror is only consulted at last resort — after central's
        // network leg. Prepending it as a priority store keeps warm forks off the network
        // entirely (and hermetic tests hermetic); a true miss still walks the remotes below.
        MavenRepo jumpkickStore = storeOnlyRepo(
                RepositorySpec.JUMPKICK_NAME,
                storeRoot.resolve("repos").resolve(RepositorySpec.JUMPKICK_NAME).toUri(),
                http,
                cas);
        MavenRepo jumpkick = storeOnlyRepo(RepositorySpec.JUMPKICK_NAME, RepositorySpec.officialUrl(), http, cas);
        MavenRepo central = storeOnlyRepo(RepositorySpec.CENTRAL, RepositorySpec.MAVEN_CENTRAL.url(), http, cas);
        RepoGroup remotes =
                new RepoGroup(List.of(jumpkick, central), List.of(RepositorySpec.JUMPKICK.groups(), List.of()));
        List<MavenRepo> leading = new ArrayList<>();
        leading.add(local);
        leading.add(jumpkickStore);
        if (extraStore != null && !extraStore.equals(storeRoot.toAbsolutePath().normalize())) {
            leading.addAll(fileRepos(extraStore));
        }
        return remotes.withReposPrepended(leading);
    }

    /** File-only {@code local} / {@code jumpkick} / {@code central} under {@code storeRoot}. */
    static RepoGroup localRepos(Path storeRoot) {
        return new RepoGroup(fileRepos(storeRoot));
    }

    private static List<MavenRepo> fileRepos(Path storeRoot) {
        Cas cas = new Cas(storeRoot);
        Http http = new Http();
        List<MavenRepo> repos = new ArrayList<>(REPOS.size());
        for (String name : REPOS) {
            Path dir = storeRoot.resolve("repos").resolve(name);
            repos.add(storeOnlyRepo(name, dir.toUri(), http, cas));
        }
        return repos;
    }

    /** Worker closures stay under {@code JK_STORE_DIR}; they do not write-through {@code ~/.m2}. */
    private static MavenRepo storeOnlyRepo(String name, URI url, Http http, Cas cas) {
        return new MavenRepo(name, url, http, cas, RepoCredential.ANONYMOUS, false);
    }

    static @Nullable Path siblingPom(Path jar) {
        String name = jar.getFileName().toString();
        if (!name.endsWith(".jar")) return null;
        return jar.resolveSibling(name.substring(0, name.length() - 4) + ".pom");
    }

    static @Nullable Path pomFor(Path worker) {
        Path sibling = siblingPom(worker);
        if (sibling != null && Files.isRegularFile(sibling)) return sibling;
        Coordinate coord = coordinateOf(worker);
        if (coord == null) return null;
        String rel = MavenLayout.pomPath(coord);
        Path found = locate(storeRootOf(worker), rel).orElse(null);
        if (found != null) return found;
        Path extra = extraStoreFor(worker);
        return extra == null ? null : locate(extra, rel).orElse(null);
    }

    /**
     * Host product store for a workspace-built worker ({@code …/target/…/jk-*-VERSION.jar}) whose
     * sandbox {@link JkDirs#store()} has no POM. {@code -Djk.host.store} from the test launcher
     * wins; otherwise the platform store with {@code JK_HOME} ignored.
     */
    static @Nullable Path extraStoreFor(Path worker) {
        if (!isWorkspaceLayout(worker)) return null;
        Path extra = configuredHostStore();
        if (extra == null) extra = unsandboxedProductStore();
        if (extra == null) return null;
        Path live = storeRootOf(worker).toAbsolutePath().normalize();
        return extra.equals(live) ? null : extra;
    }

    static @Nullable Path configuredHostStore() {
        String p = System.getProperty(HOST_STORE_PROPERTY);
        if (p == null || p.isBlank()) return null;
        Path path = Path.of(p).toAbsolutePath().normalize();
        return Files.isDirectory(path) ? path : null;
    }

    static @Nullable Path unsandboxedProductStore() {
        Path path = JkDirs.of(
                        name -> {
                            if ("JK_HOME".equals(name)) return null;
                            return System.getenv(name);
                        },
                        System.getProperty("user.home"))
                .storeDir()
                .toAbsolutePath()
                .normalize();
        return Files.isDirectory(path) ? path : null;
    }

    static boolean isWorkspaceLayout(Path worker) {
        return BuildLayout.isBuildOutput(worker);
    }

    /** Store root that owns {@code artifact} ({@code …/repos/…} parent), else {@link JkDirs#store()}. */
    static Path storeRootOf(Path artifact) {
        Path cur = artifact.toAbsolutePath().normalize().getParent();
        while (cur != null) {
            Path name = cur.getFileName();
            if (name != null && "repos".equals(name.toString())) {
                Path parent = cur.getParent();
                return parent != null ? parent : JkDirs.store();
            }
            cur = cur.getParent();
        }
        return JkDirs.store();
    }

    public static @Nullable Coordinate coordinateOf(Path jar) {
        Path abs = jar.toAbsolutePath().normalize();
        Path verDir = abs.getParent();
        Path artDir = verDir == null ? null : verDir.getParent();
        if (verDir != null && artDir != null) {
            String ver = verDir.getFileName().toString();
            String art = artDir.getFileName().toString();
            String file = abs.getFileName().toString();
            if (file.equals(art + "-" + ver + ".jar")) {
                List<String> groupSegs = new ArrayList<>();
                Path cur = artDir.getParent();
                while (cur != null) {
                    String n =
                            cur.getFileName() == null ? "" : cur.getFileName().toString();
                    Path parent = cur.getParent();
                    if (parent != null
                            && "repos".equals(fileName(parent))
                            && (RepoArtifactResolver.isFirstPartyStoreName(n)
                                    || n.equals(RepositorySpec.JUMPKICK_NAME)
                                    || n.equals(RepositorySpec.CENTRAL))) {
                        break;
                    }
                    if (!n.isEmpty()) groupSegs.add(0, n);
                    cur = parent;
                    if (groupSegs.size() > 12) break;
                }
                if (!groupSegs.isEmpty()) {
                    return Coordinate.of(String.join(".", groupSegs), art, ver);
                }
            }
        }
        String file = abs.getFileName().toString();
        if (!file.endsWith(".jar")) return null;
        String base = file.substring(0, file.length() - 4);
        for (int dash = base.indexOf('-'); dash > 0; dash = base.indexOf('-', dash + 1)) {
            String rest = base.substring(dash + 1);
            if (VERSION.matcher(rest).matches()) {
                return Coordinate.of("cc.jumpkick", base.substring(0, dash), rest);
            }
        }
        return null;
    }

    private static String fileName(Path p) {
        return p.getFileName() == null ? "" : p.getFileName().toString();
    }

    /**
     * Version mediation for the walk, Maven's nearest-wins: the tree is expanded one depth at a
     * time, and every request at a depth claims its module — in declaration order — before any
     * POM one level deeper is read. A later request for a different version of a claimed module is
     * dropped (the nearer, or earlier, request's jar serves), so a transitive that a parent POM's
     * {@code dependencyManagement} pins to another flavour can never outrank a direct declaration
     * merely because a depth-first walk reached it first. {@code pins} are the root POM's own
     * {@code dependencyManagement}: as in a Maven project they rewrite the version of every request
     * for the module below the root, at any depth, so the pinned jar is the one fetched; the root's
     * own declarations keep the versions they write out. {@code walked} keeps each
     * winning module's subtree from being expanded twice (and doubles as cycle protection).
     */
    private record WalkState(Map<String, String> mediated, Set<String> walked, Map<String, String> pins) {
        static WalkState fresh(Map<String, String> pins) {
            return new WalkState(new HashMap<>(), new HashSet<>(), Map.copyOf(pins));
        }

        Coordinate pinned(Coordinate coord) {
            String pin = pins.get(mediationKey(coord));
            if (pin == null || pin.equals(coord.version())) return coord;
            return new Coordinate(coord.group(), coord.artifact(), pin, coord.classifier(), coord.type());
        }

        /** True when {@code coord} is the version that serves its module (first claim, or the same version again). */
        boolean claim(Coordinate coord) {
            String winner = mediated.putIfAbsent(mediationKey(coord), coord.version());
            return winner == null || winner.equals(coord.version());
        }
    }

    /** A POM awaiting expansion: its effective model, the coordinate it was reached by, the exclusions on its path. */
    private record Node(EffectivePom pom, @Nullable Coordinate self, Set<String> exclusions) {}

    /** A request that won mediation at its depth and is due to be fetched. */
    private record Claim(Coordinate coord, Set<String> exclusions, boolean optional) {}

    private static String mediationKey(Coordinate coord) {
        String key = coord.group() + ":" + coord.artifact();
        if (coord.classifier() != null) key = key + ":" + coord.classifier();
        if (!"jar".equalsIgnoreCase(coord.type())) key = key + "!" + coord.type();
        return key;
    }

    /**
     * The root POM's {@code dependencyManagement} as {@code module → version}. Read from the raw
     * POM because the effective model retains managed entries only for {@code pom} packaging; the
     * effective properties resolve a {@code $&#123;…&#125;} version. Imports, blank and floating
     * versions are not pins.
     */
    static Map<String, String> rootPins(Pom raw, EffectivePom effective) {
        Map<String, String> pins = new LinkedHashMap<>();
        for (Pom.Dep m : raw.managedDependencies()) {
            if ("import".equalsIgnoreCase(m.scope())) continue;
            String version = m.version() == null ? null : substitute(m.version(), effective.properties());
            if (version == null || version.isBlank() || isUnresolvedProperty(version) || isFloating(version)) continue;
            String type = m.type() == null || m.type().isBlank() ? "jar" : m.type();
            if ("pom".equalsIgnoreCase(type)) continue;
            String classifier = m.classifier() == null || m.classifier().isBlank() ? null : m.classifier();
            pins.putIfAbsent(
                    mediationKey(new Coordinate(m.groupId(), m.artifactId(), version, classifier, type)), version);
        }
        return pins;
    }

    private static String substitute(String raw, Map<String, String> props) {
        String current = raw;
        for (int pass = 0; pass < 4 && current.contains("${"); pass++) {
            String next = current;
            for (Map.Entry<String, String> e : props.entrySet()) {
                next = next.replace("${" + e.getKey() + "}", e.getValue());
            }
            if (next.equals(current)) break;
            current = next;
        }
        return current;
    }

    /**
     * Breadth-first expansion from {@code root}. The root's own dependencies include optional ones
     * (they are its declared classpath); deeper optional edges are pruned, as Maven prunes them.
     */
    private static void walk(
            EffectivePom root,
            Map<String, String> pins,
            @Nullable Coordinate self,
            EffectivePomBuilder builder,
            RepoGroup repos,
            List<Path> out)
            throws IOException, InterruptedException {
        WalkState state = WalkState.fresh(pins);
        List<Node> level = new ArrayList<>();
        if (self != null && root.relocation() != null && root.relocation().redirects(self)) {
            Coordinate target = state.pinned(root.relocation().applyTo(self));
            if (state.claim(target) && state.walked().add(mediationKey(target))) {
                Node moved = fetch(new Claim(target, Set.of(), false), builder, repos, state, out);
                if (moved != null) level.add(moved);
            }
        } else {
            level.add(new Node(root, self, Set.of()));
        }
        boolean rootLevel = true;
        while (!level.isEmpty()) {
            // Every request at this depth claims its module before any deeper POM is read.
            List<Claim> claims = new ArrayList<>();
            for (Node node : level) {
                for (Pom.Dep d : node.pom().dependencies()) {
                    Coordinate coord = runtimeCoordinate(node.pom(), d, rootLevel, node.exclusions());
                    if (coord == null) continue;
                    // The root's own declarations state their versions, as a Maven project's do; the
                    // effective model has already filled the ones that omitted a version from the pins.
                    if (!rootLevel) coord = state.pinned(coord);
                    if (!state.claim(coord)) continue; // mediated away — the nearer request's jar serves
                    Set<String> childExcl = new HashSet<>(node.exclusions());
                    for (Pom.Dep.Exclusion ex : d.exclusions()) {
                        childExcl.add(ex.groupId() + ":" + ex.artifactId());
                    }
                    claims.add(new Claim(coord, childExcl, d.optional()));
                }
            }
            List<Node> next = new ArrayList<>();
            for (Claim claim : claims) {
                if (!state.walked().add(mediationKey(claim.coord()))) continue;
                Node child = fetch(claim, builder, repos, state, out);
                if (child != null) next.add(child);
            }
            level = next;
            rootLevel = false;
        }
    }

    /**
     * Fetch one winning request's jar onto the classpath and return its POM for the next depth;
     * {@code null} for an absent optional, a jar-only leaf, or a relocation whose target another
     * request already serves. A relocated module keeps its stub jar and expands the target instead.
     */
    private static @Nullable Node fetch(
            Claim claim, EffectivePomBuilder builder, RepoGroup repos, WalkState state, List<Path> out)
            throws IOException, InterruptedException {
        Coordinate coord = claim.coord();
        String key = coord.group() + ":" + coord.artifact() + ":" + coord.version();
        if (coord.classifier() != null) key = key + ":" + coord.classifier();
        Optional<RepoGroup.RepoFetched> art = repos.tryFetchArtifact(coord);
        if (art.isEmpty()) {
            if (claim.optional()) return null;
            String names =
                    repos.repos().stream().map(MavenRepo::name).distinct().collect(Collectors.joining(", "));
            throw new IllegalStateException(
                    "worker runtime dependency " + key + " was not found in the " + names + " repos");
        }
        out.add(art.get().fetched().cachePath().toAbsolutePath().normalize());
        EffectivePom child;
        try {
            child = builder.build(coord);
        } catch (MavenRepo.ArtifactNotFoundException e) {
            // Only the dep's OWN missing POM makes it a jar-only leaf. A missing parent or
            // imported BOM anywhere in its chain must stay loud — swallowing it silently
            // prunes the dep's whole transitive subtree and the worker dies later with
            // NoClassDefFoundError instead of a resolution-time error naming the gap.
            if (coord.equals(e.coordinate())) return null;
            throw new IllegalStateException(
                    "worker dependency " + key + " has an incomplete POM chain: " + e.getMessage(), e);
        }
        if (child.relocation() != null && child.relocation().redirects(coord)) {
            Coordinate target = state.pinned(child.relocation().applyTo(coord));
            if (!state.claim(target) || !state.walked().add(mediationKey(target))) return null;
            return fetch(new Claim(target, claim.exclusions(), claim.optional()), builder, repos, state, out);
        }
        return new Node(child, coord, claim.exclusions());
    }

    /**
     * {@code d} as a fetchable coordinate, or {@code null} when pruned. Pruning (ancestor
     * exclusions, pom-type aggregates) runs before any version policing so a dep we would never
     * use cannot abort the walk. The surviving version policy is one posture, loud: a blank
     * version (no dependencyManagement governs it), an unresolved {@code $&#123;…&#125;}, and a floating
     * selector all mean "we cannot know which jar belongs on the classpath" — dropping the dep
     * silently trades a resolution-time error for NoClassDefFoundError in the worker. Optional
     * deps are the exception: absent-if-unresolvable mirrors their fetch policy.
     */
    private static @Nullable Coordinate runtimeCoordinate(
            EffectivePom pom, Pom.Dep d, boolean rootPom, Set<String> exclusions) {
        if (!runtimeDep(d, rootPom)) return null;
        String ga = d.groupId() + ":" + d.artifactId();
        if (exclusions.contains(ga)) return null;
        String type = d.type() == null || d.type().isBlank() ? "jar" : d.type();
        if ("pom".equalsIgnoreCase(type)) return null;
        if (d.version() == null || d.version().isBlank()) {
            if (d.optional()) return null;
            throw new IllegalStateException("worker POM "
                    + pom.groupId() + ":" + pom.artifactId() + ":" + pom.version()
                    + " dependency " + ga
                    + " has no version (no dependencyManagement entry governs it)");
        }
        if (isUnresolvedProperty(d.version())) {
            if (d.optional()) return null;
            throw new IllegalStateException("worker POM "
                    + pom.groupId() + ":" + pom.artifactId() + ":" + pom.version()
                    + " dependency " + ga
                    + " has unresolved version " + d.version());
        }
        if (isFloating(d.version())) {
            if (d.optional()) return null;
            throw new IllegalStateException("worker POM " + pom.artifactId() + " has floating version for " + ga);
        }
        String classifier = d.classifier() == null || d.classifier().isBlank() ? null : d.classifier();
        return new Coordinate(d.groupId(), d.artifactId(), d.version(), classifier, type);
    }

    private static boolean runtimeDep(Pom.Dep d, boolean rootPom) {
        if (d.optional() && !rootPom) return false;
        String scope = d.scope();
        return scope == null
                || scope.isBlank()
                || "compile".equalsIgnoreCase(scope)
                || "runtime".equalsIgnoreCase(scope);
    }

    private static boolean isFloating(String version) {
        return "LATEST".equalsIgnoreCase(version) || "RELEASE".equalsIgnoreCase(version);
    }

    private static boolean isUnresolvedProperty(String version) {
        return version.contains("${");
    }

    private static Optional<Path> locate(Path storeRoot, String relativePath) {
        for (String repo : REPOS) {
            Optional<Path> hit = new RepoArtifactStore(storeRoot, repo).locate(relativePath);
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }
}
