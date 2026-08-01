// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Inverse dependency paths from declared roots to a target module ({@code jk why}). Each
 * {@link Path} starts at a root and ends at the target.
 *
 * <p>To keep output readable, returns <strong>one shortest path per distinct root</strong> (declared
 * dependency, or lockfile top when undeclared). Diamond fan-in is not expanded into every route.
 */
public final class Provenance {

    private Provenance() {}

    /**
     * @return list of paths from declared roots to {@code targetModule}. Empty if the target isn't in
     * the lockfile or is unreachable. For workspace roots prefer
     * {@link #pathsTo(JkBuild, Lockfile, String, java.nio.file.Path)} so module tomls are
     * included as roots.
     */
    public static List<Path> pathsTo(JkBuild project, Lockfile lock, String targetModule) {
        return pathsTo(project, lock, targetModule, null);
    }

    /**
     * Workspace-aware provenance: when {@code projectDir} is set and {@code project} is a workspace
     * root, declared roots are the union of every workspace module's dependencies (not only the
     * root {@code jk.toml}, which is often empty).
     *
     * <p>Uses {@code java.nio.file.Path} (not {@link Path}) for the project directory — this type
     * nests a {@code Path} record for reverse-graph steps.
     *
     * @return shortest path per root from declared roots (or lock tops) to {@code targetModule}.
     * Empty if the target isn't in the lockfile or is unreachable.
     */
    public static List<Path> pathsTo(
            JkBuild project, Lockfile lock, String targetModule, java.nio.file.Path projectDir) {
        Objects.requireNonNull(targetModule, "targetModule");

        Map<String, Lockfile.Artifact> byModule = DependencyTree.indexByModule(lock);
        String resolvedTarget = targetModule;
        if (!byModule.containsKey(resolvedTarget)) {
            // User / CLI often passes GA; lock rows are package keys (g:a:jar:).
            resolvedTarget = ga(targetModule);
            if (!byModule.containsKey(resolvedTarget)) {
                return List.of();
            }
        }

        Map<String, Set<String>> reverseDeps = reverseAdjacency(lock);
        Set<String> declaredRoots = new LinkedHashSet<>(DependencyTree.collectRoots(project, projectDir));
        return shortestPathsPerRoot(resolvedTarget, byModule, reverseDeps, declaredRoots);
    }

    /** Reverse adjacency: dep module key (package key and GA) → parent package names. */
    private static Map<String, Set<String>> reverseAdjacency(Lockfile lock) {
        Map<String, Set<String>> reverseDeps = new HashMap<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            for (String depRef : pkg.deps()) {
                String depModule = DependencyTree.stripVersion(depRef);
                reverseDeps.computeIfAbsent(depModule, k -> new TreeSet<>()).add(pkg.name());
                // also index by GA so walks that start from a GA query still find parents
                String depGa = ga(depModule);
                if (!depGa.equals(depModule)) {
                    reverseDeps.computeIfAbsent(depGa, k -> new TreeSet<>()).add(pkg.name());
                }
            }
        }
        return reverseDeps;
    }

    /**
     * Reverse BFS from {@code target}: first time each root is reached is a shortest path to that
     * root. Nodes are visited once, so diamond graphs do not explode into combinatorial paths.
     */
    private static List<Path> shortestPathsPerRoot(
            String target,
            Map<String, Lockfile.Artifact> byModule,
            Map<String, Set<String>> reverseDeps,
            Set<String> declaredRoots) {

        if (isDeclaredRoot(target, declaredRoots)) {
            return List.of(singleStep(target, byModule));
        }

        // cameFrom[parent] = child (closer to target) — reconstruct root → … → target.
        Map<String, String> cameFrom = new HashMap<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        visited.add(target);
        queue.add(target);

        // rootGa → path; LinkedHashMap keeps BFS discovery order before final sort.
        Map<String, Path> byRoot = new HashMap<>();

        while (!queue.isEmpty()) {
            String current = queue.poll();
            Set<String> parents = parentsOf(current, reverseDeps);
            boolean declared = isDeclaredRoot(current, declaredRoots);
            boolean lockTop = parents.isEmpty() && !current.equals(target);

            if (declared || lockTop) {
                String rootKey = ga(current);
                byRoot.putIfAbsent(rootKey, reconstruct(current, target, cameFrom, byModule));
                // Keep walking past a declared root: when declared root A depends on declared
                // root B which depends on the target, A's path must still be reported — one
                // shortest path per DISTINCT root, as the class contract says. The
                // visited set keeps diamonds from exploding.
            }

            for (String parent : parents) {
                if (!visited.add(parent)) continue;
                cameFrom.put(parent, current);
                queue.add(parent);
            }
        }

        List<Path> out = new ArrayList<>(byRoot.values());
        out.sort(Comparator.comparingInt((Path p) -> p.steps().size())
                .thenComparing(p -> ga(p.steps().getFirst().module()))
                .thenComparing(Path::render));
        return out;
    }

    private static Set<String> parentsOf(String module, Map<String, Set<String>> reverseDeps) {
        Set<String> parents = reverseDeps.get(module);
        if (parents == null || parents.isEmpty()) {
            parents = reverseDeps.get(ga(module));
        }
        return parents == null ? Set.of() : parents;
    }

    private static Path singleStep(String module, Map<String, Lockfile.Artifact> byModule) {
        return new Path(List.of(stepOf(module, byModule)));
    }

    private static Path reconstruct(
            String root, String target, Map<String, String> cameFrom, Map<String, Lockfile.Artifact> byModule) {
        List<Step> steps = new ArrayList<>();
        String cur = root;
        steps.add(stepOf(cur, byModule));
        while (!cur.equals(target)) {
            String next = cameFrom.get(cur);
            if (next == null) break; // defensive
            cur = next;
            steps.add(stepOf(cur, byModule));
        }
        return new Path(steps);
    }

    private static Step stepOf(String module, Map<String, Lockfile.Artifact> byModule) {
        Lockfile.Artifact pkg = byModule.get(module);
        if (pkg == null) pkg = byModule.get(ga(module));
        String version = pkg != null ? pkg.version() : "?";
        return new Step(module, version);
    }

    /** True when {@code module} is a declared root, matching either package key or GA form. */
    private static boolean isDeclaredRoot(String module, Set<String> declaredRoots) {
        if (declaredRoots.contains(module)) return true;
        String moduleGa = ga(module);
        if (declaredRoots.contains(moduleGa)) return true;
        for (String root : declaredRoots) {
            if (ga(root).equals(moduleGa)) return true;
        }
        return false;
    }

    /** {@code group:artifact} for Maven package keys; identity otherwise. */
    private static String ga(String nameOrKey) {
        if (nameOrKey == null || nameOrKey.isBlank()) return nameOrKey;
        if (cc.jumpkick.model.PackageId.isMavenPackageKey(nameOrKey)) {
            try {
                return cc.jumpkick.model.PackageId.parse(nameOrKey).ga();
            } catch (RuntimeException ignored) {
                return nameOrKey;
            }
        }
        return nameOrKey;
    }

    /** A path from a declared root (first) down to the target (last). */
    public record Path(List<Step> steps) {
        public Path {
            Objects.requireNonNull(steps, "steps");
            steps = List.copyOf(steps);
        }

        public String render() {
            return steps.stream().map(s -> s.module() + " v" + s.version()).collect(Collectors.joining(" -> "));
        }
    }

    public record Step(String module, String version) {}
}
