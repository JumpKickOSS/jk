// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.model.Coordinate;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Runtime classpath for a thin jar from its sibling Maven POM and the local repo layout.
 *
 * <p>Walks {@code compile} / {@code runtime} dependencies (transitives included; {@code provided}
 * / {@code test} / {@code optional} transitives skipped) and locates each artifact under {@code
 * repos/local}, {@code repos/jumpkick}, then {@code repos/central}. This is the install-time
 * equivalent of a Maven runtime classpath — no {@code .classpath} sidecar.
 */
public final class PomRuntimeClasspath {

    private static final List<String> REPOS = List.of("local", "jumpkick", "central");

    private PomRuntimeClasspath() {}

    /**
     * Worker jar plus located compile/runtime jars, or {@code null} when there is no sibling POM
     * (caller should fall back to a sidecar / discovery path).
     */
    public static List<Path> resolveOrNull(Path workerJar) {
        if (workerJar == null || !Files.isRegularFile(workerJar)) return null;
        Path worker = workerJar.toAbsolutePath().normalize();
        Path pom = siblingPom(worker);
        if (pom == null || !Files.isRegularFile(pom)) return null;
        Path storeRoot = storeRootOf(worker);
        List<Path> out = new ArrayList<>();
        out.add(worker);
        try {
            walk(pom, new HashSet<>(), Set.of(), out, storeRoot, true);
        } catch (RuntimeException | IOException e) {
            return null;
        }
        return out;
    }

    static Path siblingPom(Path jar) {
        String name = jar.getFileName().toString();
        if (!name.endsWith(".jar")) return null;
        return jar.resolveSibling(name.substring(0, name.length() - 4) + ".pom");
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

    private static void walk(
            Path pomFile, Set<String> visited, Set<String> exclusions, List<Path> out, Path storeRoot, boolean rootPom)
            throws IOException {
        Pom pom = PomParser.parse(Files.readAllBytes(pomFile));
        for (Pom.Dep d : pom.dependencies()) {
            if (!runtimeDep(d, rootPom)) continue;
            if (d.version() == null || d.version().isBlank()) continue;
            if (isFloating(d.version())) continue;
            String ga = d.groupId() + ":" + d.artifactId();
            if (exclusions.contains(ga)) continue;
            String type = d.type() == null || d.type().isBlank() ? "jar" : d.type();
            if ("pom".equalsIgnoreCase(type)) continue;
            String classifier = d.classifier() == null || d.classifier().isBlank() ? null : d.classifier();
            Coordinate coord = new Coordinate(d.groupId(), d.artifactId(), d.version(), classifier, type);
            String key = coord.group() + ":" + coord.artifact() + ":" + coord.version();
            if (classifier != null) key = key + ":" + classifier;
            if (!visited.add(key)) continue;
            Optional<Path> jar = locate(storeRoot, MavenLayout.artifactPath(coord));
            if (jar.isEmpty()) continue;
            Path abs = jar.get().toAbsolutePath().normalize();
            if (!out.contains(abs)) out.add(abs);
            Optional<Path> depPom = locate(storeRoot, MavenLayout.pomPath(coord));
            if (depPom.isEmpty()) {
                Path sibling = siblingPom(abs);
                if (sibling != null && Files.isRegularFile(sibling)) depPom = Optional.of(sibling);
            }
            if (depPom.isEmpty()) continue;
            Set<String> childExcl = new HashSet<>(exclusions);
            for (Pom.Dep.Exclusion ex : d.exclusions()) {
                childExcl.add(ex.groupId() + ":" + ex.artifactId());
            }
            walk(depPom.get(), visited, childExcl, out, storeRoot, false);
        }
    }

    private static boolean runtimeDep(Pom.Dep d, boolean rootPom) {
        if (d.optional() && !rootPom) return false;
        String scope = d.scope();
        if (scope == null
                || scope.isBlank()
                || "compile".equalsIgnoreCase(scope)
                || "runtime".equalsIgnoreCase(scope)) {
            return true;
        }
        return false;
    }

    private static boolean isFloating(String version) {
        return "LATEST".equalsIgnoreCase(version) || "RELEASE".equalsIgnoreCase(version);
    }

    private static Optional<Path> locate(Path storeRoot, String relativePath) {
        for (String repo : REPOS) {
            Optional<Path> hit = new RepoArtifactStore(storeRoot, repo).locate(relativePath);
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }
}
