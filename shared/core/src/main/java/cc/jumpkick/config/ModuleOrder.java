// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dependency-first (Kahn) ordering of a workspace's modules. Shared by client and engine so both
 * compute identical edges and sort order.
 */
public final class ModuleOrder {

    private ModuleOrder() {}

    /**
     * Order {@code modulesByDir} dependency-first (Kahn). On a cycle, the strays are appended in
     * declaration order so the build still tries to make progress.
     */
    public static List<Path> orderModules(Map<Path, JkBuild> modulesByDir) {
        Map<String, Path> dirByCoord = new LinkedHashMap<>();
        Map<String, Path> dirByName = new LinkedHashMap<>(); // for workspace: references
        for (var e : modulesByDir.entrySet()) {
            dirByCoord.put(coord(e.getValue()), e.getKey());
            dirByName.put(e.getValue().project().name(), e.getKey());
        }
        Map<Path, Set<Path>> edges = new LinkedHashMap<>();
        for (var e : modulesByDir.entrySet()) {
            edges.put(e.getKey(), modulePrereqs(e.getKey(), e.getValue(), dirByCoord, dirByName));
        }
        List<Path> sorted = new ArrayList<>(kahnSort(modulesByDir.keySet(), edges));
        if (sorted.size() != modulesByDir.size()) {
            // Cycle. Fall back to declaration order for the stragglers
            // so the build still tries to make progress.
            for (Path p : modulesByDir.keySet()) {
                if (!sorted.contains(p)) sorted.add(p);
            }
        }
        return sorted;
    }

    private static String coord(JkBuild m) {
        return m.project().group() + ":" + m.project().name();
    }

    /**
     * The canonical workspace-sibling match for a declared dependency: its {@code group:artifact}
     * coordinate, or — for unresolved {@code workspace = true} placeholders only — its bare sibling
     * name. Deliberately no fallback on the TOML table key ({@code Dependency.library()}): a table
     * key that happens to equal a module's name (e.g. an external, published release of a
     * first-party artifact) must stay an external artifact, not become a module edge.
     * Shared by build ordering and the dependency graph so both draw identical module edges.
     */
    public static <T> T resolveSibling(Dependency d, Map<String, T> byCoord, Map<String, T> byName) {
        T hit = byCoord.get(d.module());
        if (hit == null && d.isWorkspace()) {
            hit = byName.get(d.workspaceName());
        }
        return hit;
    }

    /**
     * Scopes that contribute to a packaged / native-image classpath (not test or dev-only).
     * Used by {@code jk native} to expand the dependency cone without pulling
     * {@code [test-dependencies]} siblings (e.g. a CLI's test harness engine).
     */
    public static final List<Scope> PRODUCTION_SCOPES =
            List.of(Scope.EXPORT, Scope.MAIN, Scope.PROVIDED, Scope.RUNTIME, Scope.PROCESSOR, Scope.PLATFORM);

    /**
     * Sibling-dep + {@code [build].order-after} prereqs (self-refs dropped). All scopes — the
     * default workspace build-order edge.
     */
    public static Set<Path> modulePrereqs(
            Path moduleDir, JkBuild m, Map<String, Path> dirByCoord, Map<String, Path> dirByName) {
        return modulePrereqs(moduleDir, m, dirByCoord, dirByName, List.of(Scope.values()));
    }

    /**
     * As {@link #modulePrereqs(Path, JkBuild, Map, Map)} restricted to {@code scopes} (e.g.
     * {@link #PRODUCTION_SCOPES} for native-image closure).
     */
    public static Set<Path> modulePrereqs(
            Path moduleDir,
            JkBuild m,
            Map<String, Path> dirByCoord,
            Map<String, Path> dirByName,
            Collection<Scope> scopes) {
        Set<Path> prereqs = new LinkedHashSet<>();
        for (Scope scope : scopes) {
            for (Dependency d : m.dependencies().of(scope)) {
                Path depDir = resolveSibling(d, dirByCoord, dirByName);
                if (depDir != null && !depDir.equals(moduleDir)) prereqs.add(depDir);
            }
        }
        // [build].order-after: build-order-only edges (no classpath/lock). Each entry names a
        // sibling by project name or group:artifact coord. Short worker names (test-runner) also
        // match first-party artifacts published as jk-<short>.
        for (String ref : m.build().allOrderAfter()) {
            Path depDir = dirByCoord.get(ref);
            if (depDir == null) depDir = dirByName.get(ref);
            if (depDir == null && !ref.contains(":") && !ref.startsWith("jk-")) {
                depDir = dirByName.get("jk-" + ref);
            }
            if (depDir != null && !depDir.equals(moduleDir)) prereqs.add(depDir);
        }
        return prereqs;
    }

    /**
     * Kahn topo-sort (prereqs first) over an explicit prereq-edge map. Returns the nodes in
     * dependency-first order; a returned list SHORTER than {@code nodes} means a cycle left some
     * nodes unplaced — callers apply their own leftover policy.
     */
    public static List<Path> kahnSort(Collection<Path> nodes, Map<Path, Set<Path>> edges) {
        Map<Path, Integer> remaining = new LinkedHashMap<>();
        for (Path n : nodes) remaining.put(n, edges.getOrDefault(n, Set.of()).size());
        Deque<Path> queue = new ArrayDeque<>();
        for (var e : remaining.entrySet()) if (e.getValue() == 0) queue.add(e.getKey());
        List<Path> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            Path next = queue.removeFirst();
            sorted.add(next);
            for (var e : edges.entrySet()) {
                if (e.getValue().contains(next)) {
                    if (remaining.merge(e.getKey(), -1, Integer::sum) == 0) queue.add(e.getKey());
                }
            }
        }
        return sorted;
    }
}
