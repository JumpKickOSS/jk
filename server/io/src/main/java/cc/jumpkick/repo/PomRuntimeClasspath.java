// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Runtime classpath for a thin jar from its Maven POM and the local repo layout.
 *
 * <p>Walks the <em>effective</em> POM ({@link EffectivePomBuilder}: parent-chain properties, BOM
 * imports, {@code dependencyManagement} version/scope defaults) and follows compile/runtime
 * dependencies (transitives included; {@code provided} / {@code test} / {@code optional}
 * transitives skipped). Artifacts are taken from {@code repos/local}, {@code repos/jumpkick}, then
 * {@code repos/central}, fetching a miss from the HTTP remotes when the session is online.
 */
public final class PomRuntimeClasspath {

    private static final List<String> REPOS = List.of("local", "jumpkick", "central");
    private static final Pattern VERSION = Pattern.compile("\\d+(?:[._-][A-Za-z0-9]+)*");

    private PomRuntimeClasspath() {}

    /**
     * Worker jar plus located compile/runtime jars. The POM is the sibling of the jar, or the
     * installed POM for the jar's Maven coordinate in the artifact store.
     *
     * @throws IllegalStateException if no POM exists or a declared runtime dep is missing
     */
    public static List<Path> resolve(Path workerJar) {
        if (workerJar == null || !Files.isRegularFile(workerJar)) {
            throw new IllegalStateException("worker jar is missing: " + workerJar);
        }
        Path worker = workerJar.toAbsolutePath().normalize();
        return resolve(worker, storeRepos(storeRootOf(worker)));
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
                    "worker " + worker + " has no Maven POM; run `jk install` to publish jar+pom to repos/local");
        }
        List<Path> out = new ArrayList<>();
        out.add(worker);
        try {
            EffectivePomBuilder builder = new EffectivePomBuilder(repos);
            Pom raw = PomParser.parse(Files.readAllBytes(pom));
            EffectivePom effective = builder.build(raw);
            walkEffective(effective, coordinateOf(worker), builder, repos, WalkState.fresh(), Set.of(), out, true);
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
        walkEffective(pom, root, builder, repos, WalkState.fresh(), Set.of(), new ArrayList<>(), true);
    }

    /**
     * {@code repos/local} plus JumpKick and Central HTTP remotes, CAS-rooted at {@code storeRoot}.
     * Local is a priority repo so {@code installLocal} artifacts outrank exclusive remote bindings.
     */
    static RepoGroup storeRepos(Path storeRoot) {
        Cas cas = new Cas(storeRoot);
        Http http = new Http();
        MavenRepo local =
                new MavenRepo("local", storeRoot.resolve("repos/local").toUri(), http, cas);
        // Launch-time resolution is overwhelmingly store-resident, but for unclaimed groups the
        // jumpkick specialist's warm mirror is only consulted at last resort — after central's
        // network leg. Prepending it as a priority store keeps warm forks off the network
        // entirely (and hermetic tests hermetic); a true miss still walks the remotes below.
        MavenRepo jumpkickStore =
                new MavenRepo("jumpkick", storeRoot.resolve("repos/jumpkick").toUri(), http, cas);
        MavenRepo jumpkick = new MavenRepo("jumpkick", RepositorySpec.officialUrl(), http, cas);
        MavenRepo central = new MavenRepo("central", RepositorySpec.MAVEN_CENTRAL.url(), http, cas);
        RepoGroup remotes =
                new RepoGroup(List.of(jumpkick, central), List.of(RepositorySpec.JUMPKICK.groups(), List.of()));
        return remotes.withReposPrepended(List.of(local, jumpkickStore));
    }

    /** File-only {@code local} / {@code jumpkick} / {@code central} under {@code storeRoot}. */
    static RepoGroup localRepos(Path storeRoot) {
        Cas cas = new Cas(storeRoot);
        Http http = new Http();
        List<MavenRepo> repos = new ArrayList<>(REPOS.size());
        for (String name : REPOS) {
            Path dir = storeRoot.resolve("repos").resolve(name);
            repos.add(new MavenRepo(name, dir.toUri(), http, cas));
        }
        return new RepoGroup(repos);
    }

    static Path siblingPom(Path jar) {
        String name = jar.getFileName().toString();
        if (!name.endsWith(".jar")) return null;
        return jar.resolveSibling(name.substring(0, name.length() - 4) + ".pom");
    }

    static Path pomFor(Path worker) {
        Path sibling = siblingPom(worker);
        if (sibling != null && Files.isRegularFile(sibling)) return sibling;
        Coordinate coord = coordinateOf(worker);
        if (coord == null) return null;
        return locate(storeRootOf(worker), MavenLayout.pomPath(coord)).orElse(null);
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

    public static Coordinate coordinateOf(Path jar) {
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
                            && (n.equals("local") || n.equals("jumpkick") || n.equals("central"))) {
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
     * Version mediation for the walk. The worker POM is a flattened resolved classpath, so the
     * root's pins are authoritative: they seed {@code mediated} before any transitive POM is
     * read, and a transitive request for a different version of an already-mediated artifact is
     * dropped (Maven nearest-wins) instead of appending a second jar whose classes would shadow
     * order-dependently. {@code walked} keeps each winning artifact's subtree from being walked
     * twice (and doubles as cycle protection).
     */
    private record WalkState(Map<String, String> mediated, Set<String> walked) {
        static WalkState fresh() {
            return new WalkState(new HashMap<>(), new HashSet<>());
        }
    }

    private static String mediationKey(Coordinate coord) {
        String key = coord.group() + ":" + coord.artifact();
        if (coord.classifier() != null) key = key + ":" + coord.classifier();
        if (!"jar".equalsIgnoreCase(coord.type())) key = key + "!" + coord.type();
        return key;
    }

    private static void walkEffective(
            EffectivePom pom,
            Coordinate self,
            EffectivePomBuilder builder,
            RepoGroup repos,
            WalkState state,
            Set<String> exclusions,
            List<Path> out,
            boolean rootPom)
            throws IOException, InterruptedException {
        if (self != null && pom.relocation() != null && pom.relocation().redirects(self)) {
            addAndWalk(pom.relocation().applyTo(self), builder, repos, state, exclusions, out, false);
            return;
        }
        if (rootPom) {
            for (Pom.Dep d : pom.dependencies()) {
                Coordinate coord = runtimeCoordinate(pom, d, true, exclusions);
                if (coord != null) state.mediated().putIfAbsent(mediationKey(coord), coord.version());
            }
        }
        for (Pom.Dep d : pom.dependencies()) {
            Coordinate coord = runtimeCoordinate(pom, d, rootPom, exclusions);
            if (coord == null) continue;
            Set<String> childExcl = new HashSet<>(exclusions);
            for (Pom.Dep.Exclusion ex : d.exclusions()) {
                childExcl.add(ex.groupId() + ":" + ex.artifactId());
            }
            addAndWalk(coord, builder, repos, state, childExcl, out, d.optional());
        }
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
    private static Coordinate runtimeCoordinate(
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

    private static void addAndWalk(
            Coordinate coord,
            EffectivePomBuilder builder,
            RepoGroup repos,
            WalkState state,
            Set<String> exclusions,
            List<Path> out,
            boolean optional)
            throws IOException, InterruptedException {
        String key = coord.group() + ":" + coord.artifact() + ":" + coord.version();
        if (coord.classifier() != null) key = key + ":" + coord.classifier();
        String gaKey = mediationKey(coord);
        String winner = state.mediated().putIfAbsent(gaKey, coord.version());
        if (winner != null && !winner.equals(coord.version())) return; // mediated away — the winner's jar serves
        if (!state.walked().add(gaKey)) return;
        Optional<RepoGroup.RepoFetched> art = repos.tryFetchArtifact(coord);
        if (art.isEmpty()) {
            if (optional) return;
            String names = repos.repos().stream()
                    .map(MavenRepo::name)
                    .distinct()
                    .collect(java.util.stream.Collectors.joining(", "));
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
            if (coord.equals(e.coordinate())) return;
            throw new IllegalStateException(
                    "worker dependency " + key + " has an incomplete POM chain: " + e.getMessage(), e);
        }
        walkEffective(child, coord, builder, repos, state, exclusions, out, false);
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
