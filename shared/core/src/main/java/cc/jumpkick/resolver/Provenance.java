// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkBuild;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Inverse dependency paths from declared roots to a target module ({@code jk why}). Each
 * {@link Path} starts at a root and ends at the target. Walks the shared {@link LockGraph}
 * substrate — build the graph once per request and query it per target.
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
            JkBuild project, Lockfile lock, String targetModule, java.nio.file.@Nullable Path projectDir) {
        return pathsTo(LockGraph.of(project, lock, projectDir), targetModule);
    }

    /**
     * As {@link #pathsTo(JkBuild, Lockfile, String, java.nio.file.Path)} against a prebuilt
     * {@link LockGraph} — callers answering several targets (e.g. {@code jk why} with a fuzzy
     * query) build the graph once and query per match.
     */
    public static List<Path> pathsTo(LockGraph graph, String targetModule) {
        Objects.requireNonNull(targetModule, "targetModule");
        if (graph.artifact(targetModule) == null) {
            return List.of();
        }
        // artifact()/parents() both alias name ↔ GA, so the raw query form walks correctly.
        return shortestPathsPerRoot(targetModule, graph);
    }

    /**
     * Reverse BFS from {@code target}: first time each root is reached is a shortest path to that
     * root. Nodes are visited once, so diamond graphs do not explode into combinatorial paths.
     */
    private static List<Path> shortestPathsPerRoot(String target, LockGraph graph) {
        if (graph.isDeclaredRoot(target)) {
            return List.of(singleStep(target, graph));
        }

        // cameFrom[parent] = child (closer to target) — reconstruct root → … → target.
        Map<String, String> cameFrom = new HashMap<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        visited.add(target);
        queue.add(target);

        // rootGa → path; BFS discovery order before the final sort.
        Map<String, Path> byRoot = new HashMap<>();

        while (!queue.isEmpty()) {
            String current = queue.poll();
            Set<String> parents = graph.parents(current);
            boolean declared = graph.isDeclaredRoot(current);
            boolean lockTop = parents.isEmpty() && !current.equals(target);

            if (declared || lockTop) {
                String rootKey = LockGraph.ga(current);
                byRoot.putIfAbsent(rootKey, reconstruct(current, target, cameFrom, graph));
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
                .thenComparing(p -> LockGraph.ga(p.steps().getFirst().module()))
                .thenComparing(Path::render));
        return out;
    }

    private static Path singleStep(String module, LockGraph graph) {
        return new Path(List.of(stepOf(module, null, graph)), graph.rootUnits(module));
    }

    private static Path reconstruct(String root, String target, Map<String, String> cameFrom, LockGraph graph) {
        List<Task> steps = new ArrayList<>();
        String cur = root;
        steps.add(stepOf(cur, null, graph));
        while (!cur.equals(target)) {
            String next = cameFrom.get(cur);
            if (next == null) break; // defensive
            String parent = cur;
            cur = next;
            steps.add(stepOf(cur, parent, graph));
        }
        return new Path(steps, graph.rootUnits(root));
    }

    /**
     * One step: the module at its locked version, with the selector its parent declared for it —
     * the manifest's for a root ({@code parent == null}), the parent row's edge otherwise.
     */
    private static Task stepOf(String module, @Nullable String parent, LockGraph graph) {
        Lockfile.Artifact pkg = graph.artifact(module);
        String version = pkg != null ? pkg.version() : "?";
        String declared = parent == null ? graph.rootSelector(module) : graph.declaredSelector(parent, module);
        return new Task(module, version, declared);
    }

    /**
     * A path from a declared root (first) down to the target (last). {@code rootUnit} is the
     * workspace units that declared the root, as {@link LockGraph#rootUnits}; null for a single
     * project or a lock top.
     */
    public record Path(List<Task> steps, @Nullable String rootUnit) {
        public Path {
            Objects.requireNonNull(steps, "steps");
            steps = List.copyOf(steps);
        }

        public String render() {
            return steps.stream().map(s -> s.module() + " v" + s.version()).collect(Collectors.joining(" -> "));
        }
    }

    /**
     * One step of a path: the module, its locked version, and the selector the step before it
     * declared ({@code null} when the lock or manifest does not say).
     */
    public record Task(
            String module, String version, @Nullable String declared) {
        public Task(String module, String version) {
            this(module, version, null);
        }
    }
}
