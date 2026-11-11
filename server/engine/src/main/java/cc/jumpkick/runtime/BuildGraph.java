// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.BuildLogicToml;
import cc.jumpkick.config.ModuleOrder;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.DependencyKind;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PathSource;
import cc.jumpkick.model.Scope;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.runtime.base.SiblingArtifacts;
import cc.jumpkick.task.RunNotices;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the build graph for an entry project: the workspace root + its modules, as one
 * topologically-sorted list of build units. The single source of truth that both the build driver
 * and {@code jk explain} consume, so they agree on exactly what builds and in what order.
 *
 * <p>Every unit is a real {@code jk.toml} project built by the normal plan ({@code
 * BuildPlanner.coreBuilder}). There is no separate "dependency unit" concept: a local sibling is
 * always a workspace module, and a git dependency (any ref type) is always a lock-pinned
 * coordinate resolved by {@link GitSourceResolution} — neither needs its own build unit here.
 * Edges point from a unit to the units that must build before it (prereqs): sibling deps in every
 * scope but the test scopes (every {@code [variants]} value's included) plus {@code
 * [build].order-after}. A sibling named only in a test scope is a test edge: it orders the unit's
 * test steps after the sibling ({@link cc.jumpkick.runtime.base.SiblingArtifacts}), never the unit,
 * so two members whose tests read each other build as Gradle builds them.
 *
 * <p>A cycle among the build-order edges, chains deeper than {@link #MAX_DEPTH} and a {@code path}
 * dependency on a member are reported as errors before any build runs.
 */
public final class BuildGraph {

    /** Guards symlink loops {@code toRealPath} can't collapse and pathological graphs. */
    public static final int MAX_DEPTH = 512;

    public enum Origin {
        ROOT,
        MODULE
    }

    /** One project to build; {@code dir} is the canonical identity key (package-private). */
    public record BuildUnit(Path dir, JkBuild manifest, String coord, Origin origin) {}

    /**
     * @param topoOrder dependency-first build order ({@code errors} empty ⇒ valid)
     * @param edges unit dir → dirs that must build before it: what admits a unit and what may not cycle
     * @param testEdges unit dir → dirs only its test scopes name, none of them in {@code edges}
     * @param errors cycle / depth-cap / missing-jk.toml
     * @param fromMemo whether {@link BuildGraph#resolve} rebuilt this graph from the preflight graph
     *     memo, which then already holds it
     */
    public record Result(
            List<BuildUnit> topoOrder,
            Map<Path, Set<Path>> edges,
            Map<Path, Set<Path>> testEdges,
            List<String> errors,
            boolean fromMemo) {
        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        /** {@code edges} and {@code testEdges} together: every sibling a unit reads at any point. */
        public Map<Path, Set<Path>> reads() {
            if (testEdges.isEmpty()) return edges;
            Map<Path, Set<Path>> all = new LinkedHashMap<>();
            for (var e : edges.entrySet()) all.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
            for (var e : testEdges.entrySet()) {
                all.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(e.getValue());
            }
            return all;
        }

        /**
         * {@code edges} plus every test edge that closes no cycle: the order a walk that reads a
         * unit's whole classpath, its tests' included, visits units in. Equals {@link #reads()} when
         * no test edge closes a cycle.
         */
        public Map<Path, Set<Path>> readOrder() {
            Map<Path, Set<Path>> all = reads();
            if (all == edges || ModuleOrder.kahnSort(all.keySet(), all).size() == all.size()) return all;
            Map<Path, Set<Path>> order = new LinkedHashMap<>();
            for (var e : edges.entrySet()) order.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
            for (var e : testEdges.entrySet()) {
                for (Path prereq : e.getValue()) {
                    if (!reaches(order, prereq, e.getKey())) {
                        order.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>())
                                .add(prereq);
                    }
                }
            }
            return order;
        }

        /**
         * What each unit's tests read of a sibling beyond its {@code edges}: the classes trees of its
         * test edges, and the fixtures and test classes it selects of any sibling.
         */
        public SiblingArtifacts.TestReads testReads() {
            Map<String, Path> dirByCoord = new LinkedHashMap<>();
            Map<String, Path> dirByName = new LinkedHashMap<>();
            for (BuildUnit u : topoOrder) {
                dirByCoord.put(u.coord(), u.dir());
                dirByName.put(u.manifest().project().name(), u.dir());
            }
            Map<Path, Set<Path>> fixtures = new LinkedHashMap<>();
            Map<Path, Set<Path>> testClasses = new LinkedHashMap<>();
            for (BuildUnit u : topoOrder) {
                for (Scope scope : ModuleOrder.TEST_ONLY_SCOPES) {
                    for (Dependency d : u.manifest().dependencies().of(scope)) {
                        Path dir = ModuleOrder.resolveSibling(d, dirByCoord, dirByName);
                        if (dir == null || dir.equals(u.dir())) continue;
                        if (d.fixtures())
                            fixtures.computeIfAbsent(u.dir(), k -> new LinkedHashSet<>())
                                    .add(dir);
                        if (d.kind() == DependencyKind.TESTS) {
                            testClasses
                                    .computeIfAbsent(u.dir(), k -> new LinkedHashSet<>())
                                    .add(dir);
                        }
                    }
                }
            }
            return new SiblingArtifacts.TestReads(testEdges, fixtures, testClasses);
        }

        /** {@link BuildGraph#maxReadyWidth(List, Map)} over this graph's own order and edges. */
        public int maxReadyWidth() {
            return BuildGraph.maxReadyWidth(topoOrder, edges);
        }

        /**
         * Subgraph of {@code keep} dirs (identity via {@link BuildGraph#canonicalPath}). Edges that
         * leave the set are dropped. Errors are copied (caller should not restrict a failed graph).
         */
        public Result restrict(Set<Path> keep) {
            if (keep == null || keep.isEmpty()) return this;
            Set<Path> canon = new HashSet<>();
            for (Path p : keep) canon.add(BuildGraph.canonicalPath(p));
            List<BuildUnit> units = new ArrayList<>();
            for (BuildUnit u : topoOrder) {
                if (canon.contains(BuildGraph.canonicalPath(u.dir()))) units.add(u);
            }
            return new Result(units, keepWithin(edges, canon), keepWithin(testEdges, canon), errors, fromMemo);
        }

        private static Map<Path, Set<Path>> keepWithin(Map<Path, Set<Path>> edges, Set<Path> canon) {
            Map<Path, Set<Path>> e = new LinkedHashMap<>();
            for (var en : edges.entrySet()) {
                if (!canon.contains(BuildGraph.canonicalPath(en.getKey()))) continue;
                Set<Path> pr = new LinkedHashSet<>();
                for (Path p : en.getValue()) {
                    if (canon.contains(BuildGraph.canonicalPath(p))) pr.add(p);
                }
                e.put(en.getKey(), pr);
            }
            return e;
        }

        /** Whether {@code to} is reachable from {@code from} over {@code edges}. */
        private static boolean reaches(Map<Path, Set<Path>> edges, Path from, Path to) {
            Set<Path> seen = new HashSet<>();
            ArrayDeque<Path> queue = new ArrayDeque<>(List.of(from));
            while (!queue.isEmpty()) {
                Path p = queue.poll();
                if (p.equals(to)) return true;
                if (seen.add(p)) queue.addAll(edges.getOrDefault(p, Set.of()));
            }
            return false;
        }
    }

    private BuildGraph() {}

    /**
     * The widest wave of independent modules — the most that could build concurrently once
     * dependencies are honored. Simulates the same ready-set draining the build scheduler does (a
     * module is ready when all its in-graph prereqs are done), returning the largest wave. Drives
     * the concurrency the build and its ETA calibrate to, so {@code jk build} and {@code jk
     * explain} agree.
     */
    public static int maxReadyWidth(List<BuildUnit> units, Map<Path, Set<Path>> edges) {
        Set<Path> unitDirs = new LinkedHashSet<>();
        for (BuildUnit u : units) unitDirs.add(u.dir());
        return maxReadyWidth(unitDirs, edges);
    }

    /**
     * {@link #maxReadyWidth(List, Map)} over bare dirs, for callers that hold a forecast rather than
     * a unit list.
     *
     * <p>The forecast and the executor have to agree on this number or they are describing different
     * builds: it is what divides the machine into each module's {@code -w} auto share, so a
     * disagreement is a suite priced for the wrong runner count. They disagreed — the executor asked
     * this, the ETA counted dirty modules instead — and a one-file edit that really ran engine's
     * suite on 24 runners was forecast on 1, for a 91 s estimate against a 21 s build. Dependent
     * modules never widen a build: engine and cli are both dirty after a spine edit, but cli waits
     * for engine, so the width is 1 and each gets the whole machine in turn.
     */
    public static int maxReadyWidth(Set<Path> dirs, Map<Path, Set<Path>> edges) {
        int max = 1;
        for (List<Path> wave : waves(dirs, edges)) max = Math.max(max, wave.size());
        return max;
    }

    /**
     * The waves the ready-set drains in: every dir in a wave has all of its in-graph prereqs in an
     * earlier one, and none in its own. Dependency order holds between waves and nothing inside a
     * wave depends on anything else there, which is what lets a reader walk a wave concurrently.
     *
     * <p>A dir a cycle leaves unreachable is dropped rather than looped on: the graph resolve
     * reports the cycle, and nothing here should spin waiting for a prereq that never arrives. A
     * caller that must cover every dir checks what came back.
     */
    public static List<List<Path>> waves(Set<Path> dirs, Map<Path, Set<Path>> edges) {
        Set<Path> done = new HashSet<>();
        List<Path> remaining = new ArrayList<>(dirs);
        List<List<Path>> waves = new ArrayList<>();
        while (!remaining.isEmpty()) {
            List<Path> ready = remaining.stream()
                    .filter(d -> edges.getOrDefault(d, Set.of()).stream()
                            .filter(dirs::contains)
                            .allMatch(done::contains))
                    .toList();
            if (ready.isEmpty()) break; // defensive: a cycle would otherwise spin
            waves.add(ready);
            done.addAll(ready);
            remaining.removeAll(ready);
        }
        return waves;
    }

    /**
     * Resolve the graph rooted at {@code entryDir}/{@code entry}. On a preflight graph-memo hit it
     * rebuilds topo + edges + re-parsed manifests without {@link WorkspaceLoader}, and the result
     * says so in {@link Result#fromMemo}.
     */
    public static Result resolve(Path entryDir, JkBuild entry) throws IOException {
        // try memo first — skip WorkspaceLoader membership walk when structure is warm.
        var memo = PreflightMemo.tryLoadGraph(entryDir);
        if (memo.isPresent()) {
            Perf.note("graph-memo hit", "units", memo.get().topoOrder().size());
            return memo.get();
        }
        Builder b = new Builder();
        if (entry.isWorkspaceRoot()) {
            b.addWorkspace(entryDir, entry, Origin.MODULE);
        } else {
            b.addUnit(b.canonical(entryDir), entry, Origin.ROOT);
        }
        List<BuildUnit> order = b.errors.isEmpty() ? b.topoSort() : List.of();
        return new Result(order, b.edges, b.testEdges, b.errors, false);
    }

    /**
     * Order workspace modules dependency-first (build-order sibling deps + {@code order-after}).
     * Paths are returned as given. On a cycle, remaining modules are appended in declaration order.
     */
    public static List<Path> orderModules(Map<Path, JkBuild> modulesByDir) {
        return ModuleOrder.orderModules(modulesByDir);
    }

    /**
     * As {@link #orderModules(Map)} for a caller that knows the workspace root, so the order also
     * carries the members a published POM edge substitutes.
     */
    public static List<Path> orderModules(Path root, Map<Path, JkBuild> modulesByDir) {
        return ModuleOrder.orderModules(root, modulesByDir);
    }

    /** See {@link cc.jumpkick.config.ModuleOrder#kahnSort} — the one shared topo-sort. */
    private static List<Path> kahnSort(Collection<Path> nodes, Map<Path, Set<Path>> edges) {
        return ModuleOrder.kahnSort(nodes, edges);
    }

    private static final class Builder {
        final Map<Path, BuildUnit> units = new LinkedHashMap<>(); // canonical dir → unit
        final Map<Path, Set<Path>> edges = new LinkedHashMap<>(); // dir → prereq dirs
        final Map<Path, Set<Path>> testEdges = new LinkedHashMap<>(); // dir → test-only prereq dirs
        final Map<String, Path> dirByCoord = new LinkedHashMap<>();
        final Map<String, Path> dirByName = new LinkedHashMap<>();
        final List<String> errors = new ArrayList<>();

        void addUnit(Path canonicalDir, JkBuild m, Origin origin) {
            noteStrayScripts(canonicalDir);
            String coord = m.project().group() + ":" + m.project().name();
            units.putIfAbsent(canonicalDir, new BuildUnit(canonicalDir, m, coord, origin));
            edges.putIfAbsent(canonicalDir, new LinkedHashSet<>());
        }

        /** Scripts at the top of {@code dir}'s .jk/ run no more: the build says so, once. */
        private static void noteStrayScripts(Path dir) {
            RunNotices.warnOnce("build-logic-stray:" + dir, () -> {
                String note = BuildLogicToml.strayScriptsNote(dir);
                return note == null ? null : "[build] " + note;
            });
        }

        void addEdge(Path from, Path prereq) {
            if (!from.equals(prereq))
                edges.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(prereq);
        }

        /** Add every module of a workspace as a unit, then wire module-order edges. */
        void addWorkspace(Path wsRoot, JkBuild root, Origin moduleOrigin) throws IOException {
            Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(wsRoot, root);
            // A workspace root that carries its own sources is itself a build unit — it
            // compiles and locks like any module (its coordinate is already in the
            // collision set). A pure coordinator root (no src/) is not built; its merged
            // deps are still validated up front by the whole-workspace lock check.
            Path rootDir = canonical(wsRoot);
            // Before the root is known to be a unit: a root whose scripts were left at the top of
            // .jk/ has no build logic any more, and is exactly the root that would never say so.
            noteStrayScripts(rootDir);
            boolean rootHasSources = CompileSupport.hasSources(rootDir);
            // A sourceless root with `.jk/scripts/` is a unit too. Its plan compiles and packages
            // nothing, but it has an `after-build` script to run, and the only way to run that
            // once — after every member, with the scheduler and the report it already has — is to
            // be in the graph. Without this the directory is silently ignored.
            boolean rootHasLogicDir = BuildLogicToml.resolve(rootDir).isPresent();
            // A root with guards is a unit for the same reason: its model and tree lanes run once,
            // after every member, with the scheduler and the report the graph already has.
            boolean rootHasGuards = PlannerGuards.enabledAt(rootDir);
            boolean rootHasBuildLogic = !rootHasSources && (rootHasLogicDir || rootHasGuards);
            boolean rootHasGuard = rootHasLogicDir && BuildLogicToml.hasStem(rootDir, "guard");
            boolean rootBuildable = rootHasSources || rootHasBuildLogic;
            if (rootBuildable) {
                addUnit(rootDir, root, Origin.ROOT);
                dirByCoord.put(root.project().group() + ":" + root.project().name(), rootDir);
                dirByName.put(root.project().name(), rootDir);
            }
            for (var e : modules.entrySet()) {
                Path c = canonical(e.getKey());
                addUnit(c, e.getValue(), moduleOrigin);
                dirByCoord.put(
                        e.getValue().project().group() + ":"
                                + e.getValue().project().name(),
                        c);
                dirByName.put(e.getValue().project().name(), c);
            }
            for (var e : modules.entrySet()) {
                Path moduleDir = canonical(e.getKey());
                addModuleEdges(rootDir, moduleDir, e.getValue());
            }
            // The root is a first-class unit whose inter-unit deps are explicit
            // (Cargo/uv style), so its edges come from its declared workspace deps
            // like any member — the root may depend on members, members on the root,
            // or neither. dirByName/dirByCoord already include the root (added above),
            // so member->root deps resolve to an edge here too.
            if (rootBuildable) {
                addModuleEdges(rootDir, rootDir, root);
            }
            // `after-build` means after every member, so the sourceless root depends on all of
            // them. A root that builds nothing publishes nothing, so no member can depend back on
            // it and these edges cannot close a cycle.
            if (rootHasBuildLogic || (rootHasSources && (rootHasGuard || rootHasGuards))) {
                for (Path moduleDir : modules.keySet()) {
                    addEdge(rootDir, canonical(moduleDir));
                }
            }
        }

        /** Every scope: what the path-dependency check reads. */
        private static final List<Scope> ALL_SCOPES = List.of(Scope.values());

        /**
         * Build-order prereq edges (sibling deps outside the test scopes + {@code
         * [build].order-after}) and test edges. Delegates edge resolution to the shared {@link
         * ModuleOrder} so the graph and {@link BuildGraph#orderModules} stay in lock-step.
         */
        private void addModuleEdges(Path rootDir, Path moduleDir, JkBuild m) {
            for (Path prereq :
                    ModuleOrder.modulePrereqs(moduleDir, m, dirByCoord, dirByName, ModuleOrder.BUILD_ORDER_SCOPES)) {
                addEdge(moduleDir, prereq);
            }
            // A published POM edge onto a member puts that member's output on this module's
            // classpath without any declared dependency naming it; the wave has to know.
            for (Path prereq :
                    ModuleOrder.substitutedPrereqs(rootDir, moduleDir, m, ModuleOrder.BUILD_ORDER_SCOPES, dirByCoord)) {
                addEdge(moduleDir, prereq);
            }
            Set<Path> test = new LinkedHashSet<>(ModuleOrder.testOnlyPrereqs(moduleDir, m, dirByCoord, dirByName));
            test.addAll(
                    ModuleOrder.substitutedPrereqs(rootDir, moduleDir, m, ModuleOrder.TEST_ONLY_SCOPES, dirByCoord));
            test.removeAll(edges.getOrDefault(moduleDir, Set.of()));
            test.remove(moduleDir);
            if (!test.isEmpty())
                testEdges.computeIfAbsent(moduleDir, k -> new LinkedHashSet<>()).addAll(test);
            rejectPathToMember(moduleDir, m);
        }

        /**
         * A {@code path} dependency builds its target apart from the workspace and publishes it under
         * the target's own coordinate, which a member's coordinate shadows: the member is never on the
         * consumer's classpath that way. A member names another with {@code workspace = true}.
         */
        private void rejectPathToMember(Path moduleDir, JkBuild m) {
            for (Scope scope : ALL_SCOPES) {
                for (Dependency d : ModuleOrder.declared(m, scope)) {
                    PathSource source = d.pathSource();
                    if (source == null) continue;
                    BuildUnit target = units.get(canonical(source.dir(moduleDir)));
                    if (target == null || target.dir().equals(moduleDir)) continue;
                    errors.add(m.project().name() + ": `" + d.library() + " = { path = \"" + source.rawPath()
                            + "\" }` names workspace member " + target.coord() + "; write `" + d.library()
                            + " = { workspace = true }`");
                }
            }
        }

        /** Kahn topo-sort (prereqs first); a leftover set means a cycle → an error naming it. */
        List<BuildUnit> topoSort() {
            List<Path> sorted = kahnSort(units.keySet(), edges);
            if (sorted.size() != units.size()) {
                Set<Path> stuck = new LinkedHashSet<>(units.keySet());
                sorted.forEach(stuck::remove);
                errors.add(cycleError(stuck));
                return List.of();
            }
            return sorted.stream().map(units::get).toList();
        }

        /**
         * One cycle among {@code stuck} (the units Kahn could not place), its members in order. A
         * leftover that only depends on a cycle is not in it.
         */
        private String cycleError(Set<Path> stuck) {
            List<Path> cycle = findCycle(stuck);
            StringBuilder msg = new StringBuilder("dependency cycle: ");
            for (Path p : cycle) msg.append(nameOf(p)).append(" → ");
            return msg.append(nameOf(cycle.getFirst())).toString();
        }

        /** A cycle among {@code stuck}: walk prereqs inside the set until a unit repeats. */
        private List<Path> findCycle(Set<Path> stuck) {
            List<Path> path = new ArrayList<>();
            Map<Path, Integer> at = new LinkedHashMap<>();
            Path cur = stuck.iterator().next();
            while (!at.containsKey(cur)) {
                at.put(cur, path.size());
                path.add(cur);
                Path next = null;
                for (Path p : edges.getOrDefault(cur, Set.of())) {
                    if (stuck.contains(p)) {
                        next = p;
                        break;
                    }
                }
                // Every leftover keeps at least one leftover prereq, or Kahn would have placed it.
                if (next == null) return path;
                cur = next;
            }
            return path.subList(at.get(cur), path.size());
        }

        private String nameOf(Path dir) {
            BuildUnit u = units.get(dir);
            return u == null ? dir.toString() : u.manifest().project().name();
        }

        Path canonical(Path p) {
            return canonicalPath(p);
        }
    }

    /**
     * The graph's node identity: real path when resolvable, else absolute-normalized. Callers
     * looking nodes/edges up by client-supplied dirs must canonicalize with this same function —
     * a normalize-only lookup silently misses under symlinked checkouts.
     */
    public static Path canonicalPath(Path p) {
        // Successful resolutions are memoized for one request: the preflight canonicalizes the same
        // module dirs many times. A failed one (the path does not exist yet) is not, so a later
        // create resolves fresh, and the next build sees a symlink retargeted between builds.
        try {
            return RequestScope.current().get(new Canonical(p), k -> realPath(k.path()));
        } catch (UncheckedIOException unresolved) {
            return p.toAbsolutePath().normalize();
        }
    }

    /** The request-scoped key of {@link #canonicalPath}. */
    private record Canonical(Path path) {}

    private static Path realPath(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
