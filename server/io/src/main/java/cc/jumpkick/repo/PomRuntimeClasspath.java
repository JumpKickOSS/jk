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
import java.util.HashSet;
import java.util.List;
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
            walkEffective(effective, coordinateOf(worker), builder, repos, new HashSet<>(), Set.of(), out, true);
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
        walkEffective(pom, root, builder, repos, new HashSet<>(), Set.of(), new ArrayList<>(), true);
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
        MavenRepo jumpkick = new MavenRepo("jumpkick", RepositorySpec.JUMPKICK.url(), http, cas);
        MavenRepo central = new MavenRepo("central", RepositorySpec.MAVEN_CENTRAL.url(), http, cas);
        RepoGroup remotes =
                new RepoGroup(List.of(jumpkick, central), List.of(RepositorySpec.JUMPKICK.groups(), List.of()));
        return remotes.withReposPrepended(List.of(local));
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

    private static void walkEffective(
            EffectivePom pom,
            Coordinate self,
            EffectivePomBuilder builder,
            RepoGroup repos,
            Set<String> visited,
            Set<String> exclusions,
            List<Path> out,
            boolean rootPom)
            throws IOException, InterruptedException {
        if (self != null && pom.relocation() != null && pom.relocation().redirects(self)) {
            addAndWalk(pom.relocation().applyTo(self), builder, repos, visited, exclusions, out, false);
            return;
        }
        for (Pom.Dep d : pom.dependencies()) {
            if (!runtimeDep(d, rootPom)) continue;
            if (d.version() == null || d.version().isBlank()) continue;
            if (isUnresolvedProperty(d.version())) {
                throw new IllegalStateException("worker POM "
                        + pom.groupId()
                        + ":"
                        + pom.artifactId()
                        + ":"
                        + pom.version()
                        + " dependency "
                        + d.groupId()
                        + ":"
                        + d.artifactId()
                        + " has unresolved version "
                        + d.version());
            }
            if (isFloating(d.version())) {
                throw new IllegalStateException("worker POM "
                        + pom.artifactId()
                        + " has floating version for "
                        + d.groupId()
                        + ":"
                        + d.artifactId());
            }
            String ga = d.groupId() + ":" + d.artifactId();
            if (exclusions.contains(ga)) continue;
            String type = d.type() == null || d.type().isBlank() ? "jar" : d.type();
            if ("pom".equalsIgnoreCase(type)) continue;
            String classifier = d.classifier() == null || d.classifier().isBlank() ? null : d.classifier();
            Coordinate coord = new Coordinate(d.groupId(), d.artifactId(), d.version(), classifier, type);
            Set<String> childExcl = new HashSet<>(exclusions);
            for (Pom.Dep.Exclusion ex : d.exclusions()) {
                childExcl.add(ex.groupId() + ":" + ex.artifactId());
            }
            addAndWalk(coord, builder, repos, visited, childExcl, out, d.optional());
        }
    }

    private static void addAndWalk(
            Coordinate coord,
            EffectivePomBuilder builder,
            RepoGroup repos,
            Set<String> visited,
            Set<String> exclusions,
            List<Path> out,
            boolean optional)
            throws IOException, InterruptedException {
        String key = coord.group() + ":" + coord.artifact() + ":" + coord.version();
        if (coord.classifier() != null) key = key + ":" + coord.classifier();
        if (!visited.add(key)) return;
        Optional<RepoGroup.RepoFetched> art = repos.tryFetchArtifact(coord);
        if (art.isEmpty()) {
            if (optional) return;
            throw new IllegalStateException("worker runtime dependency " + key
                    + " is not in repos/local, repos/jumpkick, or repos/central; run `jk install`");
        }
        Path abs = art.get().fetched().cachePath().toAbsolutePath().normalize();
        if (!out.contains(abs)) out.add(abs);
        EffectivePom child;
        try {
            child = builder.build(coord);
        } catch (MavenRepo.ArtifactNotFoundException e) {
            return;
        }
        walkEffective(child, coord, builder, repos, visited, exclusions, out, false);
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
