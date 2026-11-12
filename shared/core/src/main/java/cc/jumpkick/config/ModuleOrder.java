// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.Variants;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Dependency-first (Kahn) ordering of a workspace's modules. Shared by client and engine so both
 * compute identical edges and sort order.
 */
public final class ModuleOrder {

    private ModuleOrder() {}

    /**
     * The scopes only a module's tests read. A sibling named in these alone is a test edge: it
     * orders the module's test steps after the sibling, never the module itself.
     */
    public static final List<Scope> TEST_ONLY_SCOPES = List.of(Scope.TEST, Scope.TEST_PROCESSOR, Scope.TEST_DEV);

    /** Every scope but {@link #TEST_ONLY_SCOPES}: the edges that order modules and that may not cycle. */
    public static final List<Scope> BUILD_ORDER_SCOPES = Arrays.stream(Scope.values())
            .filter(s -> !TEST_ONLY_SCOPES.contains(s))
            .toList();

    /**
     * Order {@code modulesByDir} dependency-first (Kahn) over the {@link #BUILD_ORDER_SCOPES} edges.
     * On a cycle, the strays are appended in declaration order so the build still tries to make
     * progress.
     */
    public static List<Path> orderModules(Map<Path, JkBuild> modulesByDir) {
        return orderModules(null, modulesByDir);
    }

    /**
     * As {@link #orderModules(Map)}, also drawing the edges a published POM edge onto a member
     * implies. {@code root} is the workspace root whose lock records them; null skips that read.
     */
    public static List<Path> orderModules(@Nullable Path root, Map<Path, JkBuild> modulesByDir) {
        Map<String, Path> dirByCoord = new LinkedHashMap<>();
        Map<String, Path> dirByName = new LinkedHashMap<>(); // for workspace: references
        for (var e : modulesByDir.entrySet()) {
            dirByCoord.put(coord(e.getValue()), e.getKey());
            dirByName.put(e.getValue().project().name(), e.getKey());
        }
        Map<Path, Set<Path>> edges = new LinkedHashMap<>();
        for (var e : modulesByDir.entrySet()) {
            Set<Path> prereqs = new LinkedHashSet<>(
                    modulePrereqs(e.getKey(), e.getValue(), dirByCoord, dirByName, BUILD_ORDER_SCOPES));
            prereqs.addAll(substitutedPrereqs(root, e.getKey(), e.getValue(), BUILD_ORDER_SCOPES, dirByCoord));
            edges.put(e.getKey(), prereqs);
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
     * name, or the {@code group:name} pair a group-qualified placeholder spells. Deliberately no fallback on the TOML table key ({@code Dependency.library()}): a table
     * key that happens to equal a module's name (e.g. an external, published release of a
     * first-party artifact) must stay an external artifact, not become a module edge.
     * Shared by build ordering and the dependency graph so both draw identical module edges.
     */
    public static <T> @Nullable T resolveSibling(Dependency d, Map<String, T> byCoord, Map<String, T> byName) {
        T hit = byCoord.get(d.module());
        if (hit == null && d.isWorkspace()) {
            String coordinate = Dependency.workspaceCoordinate(d.module());
            hit = coordinate != null ? byCoord.get(coordinate) : byName.get(d.workspaceName());
        }
        return hit;
    }

    /**
     * Prereq dirs for the members {@code m} reaches only through a published POM edge. The
     * resolver serves that edge from the member, so the member's output is on this module's
     * classpath and has to be built first — nothing in {@code m}'s own dependencies says so. The
     * walk starts from every dependency {@link #declared} names, a {@code [variants]} value's
     * included, whichever value a build selects.
     */
    public static Set<Path> substitutedPrereqs(
            @Nullable Path root, Path moduleDir, JkBuild m, Collection<Scope> scopes, Map<String, Path> dirByCoord) {
        if (root == null) return Set.of();
        List<Dependency> declared = new ArrayList<>();
        for (Scope scope : scopes) declared.addAll(declared(m, scope));
        Set<Path> out = new LinkedHashSet<>();
        for (String member : PomSubstitution.membersBehindPublishedEdges(root, declared, dirByCoord.keySet())) {
            Path dir = dirByCoord.get(member);
            if (dir != null && !dir.equals(moduleDir)) out.add(dir);
        }
        return out;
    }

    /**
     * Scopes that contribute to a packaged / native-image classpath (not test or dev-only).
     * Used by {@code jk native} to expand the dependency cone without pulling
     * {@code [test-dependencies]} siblings (e.g. a CLI's test harness engine).
     */
    public static final List<Scope> PRODUCTION_SCOPES =
            List.of(Scope.EXPORT, Scope.MAIN, Scope.PROVIDED, Scope.RUNTIME, Scope.PROCESSOR, Scope.PLATFORM);

    /**
     * Sibling-dep + {@code [build].order-after} prereqs (self-refs dropped) in every scope: each
     * sibling the module reads at any point, test edges included. A {@code [variants]} value's
     * dependencies count whichever value a build selects.
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
        Set<Path> prereqs = siblingsIn(moduleDir, m, dirByCoord, dirByName, scopes);
        // [build].order-after: build-order-only edges (no classpath/lock), each naming a sibling by
        // project name or group:artifact coord.
        for (String ref : m.build().orderAfter()) {
            Path depDir = dirByCoord.get(ref);
            if (depDir == null) depDir = dirByName.get(ref);
            if (depDir != null && !depDir.equals(moduleDir)) prereqs.add(depDir);
        }
        // [build].test-plugin-jars: the worker (or rule pack) whose jar the tests load builds first.
        for (String ref : m.build().testPluginJars()) {
            Path depDir = testPluginJarSibling(ref, dirByCoord, dirByName);
            if (depDir != null && !depDir.equals(moduleDir)) prereqs.add(depDir);
        }
        return prereqs;
    }

    /**
     * The sibling a {@code [build] test-plugin-jars} entry names, read the way the test JVM's jar
     * property is: a coordinate names exactly that module, and a short name is the first-party
     * worker {@code jk-<short>} before any sibling that merely carries the short name. A rule pack
     * is often named for the framework its worker serves ({@code cc.jumpkick.guards:helidon} beside
     * {@code jk-helidon}), so a name-first lookup ordered the tests after the pack and left the
     * worker whose jar they load unbuilt.
     */
    public static <T> @Nullable T testPluginJarSibling(String ref, Map<String, T> byCoord, Map<String, T> byName) {
        if (ref.contains(":")) return byCoord.get(ref);
        T worker = ref.startsWith("jk-") ? null : byName.get("jk-" + ref);
        return worker != null ? worker : byName.get(ref);
    }

    /**
     * The siblings {@code m} names in {@link #TEST_ONLY_SCOPES} and in no {@link
     * #BUILD_ORDER_SCOPES} table: what only its tests read. {@code [build].order-after} is never a
     * test edge.
     */
    public static Set<Path> testOnlyPrereqs(
            Path moduleDir, JkBuild m, Map<String, Path> dirByCoord, Map<String, Path> dirByName) {
        Set<Path> test = siblingsIn(moduleDir, m, dirByCoord, dirByName, TEST_ONLY_SCOPES);
        test.removeAll(modulePrereqs(moduleDir, m, dirByCoord, dirByName, BUILD_ORDER_SCOPES));
        return test;
    }

    /** The sibling dirs {@code m} declares in {@code scopes} (self-refs dropped). */
    private static Set<Path> siblingsIn(
            Path moduleDir,
            JkBuild m,
            Map<String, Path> dirByCoord,
            Map<String, Path> dirByName,
            Collection<Scope> scopes) {
        Set<Path> siblings = new LinkedHashSet<>();
        for (Scope scope : scopes) {
            for (Dependency d : declared(m, scope)) {
                Path depDir = resolveSibling(d, dirByCoord, dirByName);
                if (depDir != null && !depDir.equals(moduleDir)) siblings.add(depDir);
            }
        }
        return siblings;
    }

    /** {@code m}'s dependencies in {@code scope}: its own table's, then every variant value's. */
    public static List<Dependency> declared(JkBuild m, Scope scope) {
        List<Dependency> out = new ArrayList<>(m.dependencies().of(scope));
        for (Variants.Dimension dimension : m.variants().dimensions()) {
            for (Variants.Value value : dimension.values().values()) {
                out.addAll(value.dependencies().getOrDefault(scope, List.of()));
            }
        }
        return out;
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
