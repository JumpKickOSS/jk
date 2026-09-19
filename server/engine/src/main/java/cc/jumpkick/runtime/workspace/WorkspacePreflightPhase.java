// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ModuleOrder;
import cc.jumpkick.config.WorkspaceCone;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.EffortWeights;
import cc.jumpkick.runtime.FirstPartyPins;
import cc.jumpkick.runtime.PreflightMemo;
import cc.jumpkick.runtime.base.CompileSupport;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NullMarked;

/** Parses, locks, resolves, selects, forecasts, and restores before planning begins. */
@NullMarked
public final class WorkspacePreflightPhase {

    private WorkspacePreflightPhase() {}

    sealed interface Outcome permits Completed, Ready {}

    record Completed(WorkspaceResult result) implements Outcome {}

    record Ready(Context context) implements Outcome {}

    /** Typed state handed from preflight to resource planning. */
    record Context(
            WorkspaceRequest request,
            JkBuild entry,
            BuildGraph.Result graph,
            List<BuildGraph.BuildUnit> units,
            Set<Path> moduleDirs,
            Set<Path> jarConsumed,
            Set<Path> dirty,
            Optional<BuildForecasting.Preflight> forecast) {}

    static Outcome run(WorkspaceRequest request, WorkspaceBuildListener listener) {
        return run(request, listener, Clock.SYSTEM);
    }

    /** {@link #run(WorkspaceRequest, WorkspaceBuildListener)} on {@code clock}, which times the lock freshen. */
    static Outcome run(WorkspaceRequest request, WorkspaceBuildListener listener, Clock clock) {
        JkBuild entryBuild;
        try {
            entryBuild = JkBuildParser.parse(ManifestPaths.manifestIn(request.entryDir()));
        } catch (Exception e) {
            return completed(false, 2, List.of(Errors.text(e)));
        }

        Optional<Completed> lockFailure = freshenLock(request, entryBuild, listener, clock);
        if (lockFailure.isPresent()) return lockFailure.orElseThrow();

        listener.onPreflight("graph", 0, 0, "Resolving module graph…");
        BuildGraph.Result graph;
        try {
            graph = BuildGraph.resolve(request.entryDir(), entryBuild);
        } catch (IOException e) {
            return completed(false, 2, List.of(Errors.text(e)));
        }
        if (graph.hasErrors()) {
            return completed(false, 2, List.copyOf(graph.errors()));
        }

        List<BuildGraph.BuildUnit> units = graph.topoOrder();
        followFirstPartyPins(request, units, listener);
        boolean graphMemoHit = PreflightMemo.graphStructureMatches(request.entryDir(), graph);
        PreflightMemo.storeGraph(request.entryDir(), graph);
        if (graphMemoHit) Perf.note("preflight-graph-memo structure-match", "units", units.size());
        listener.onPreflight("graph", 1, 1, units.size() + " modules" + (graphMemoHit ? " (memo)" : ""));
        WorkspaceSpec requested = request.spec();
        String nothing =
                requested != null && requested.hasSelection() ? null : NothingToBuild.verdict(units, entryBuild);
        if (nothing != null) {
            return completed(false, Exit.CONFIG, List.of(nothing));
        }

        graph = applySelectionCone(graph, request);
        units = graph.topoOrder();
        if (units.isEmpty()) {
            WorkspaceSpec spec = request.spec();
            if (spec != null && spec.hasSelection()) {
                String selection = spec.selectedModules().stream()
                        .map(Path::toString)
                        .sorted()
                        .collect(Collectors.joining(", "));
                return completed(false, 2, List.of("selection matched no workspace module: " + selection));
            }
            return completed(true, 0, List.of());
        }

        Set<Path> moduleDirs = new LinkedHashSet<>();
        for (BuildGraph.BuildUnit unit : units) moduleDirs.add(unit.dir());
        Set<Path> jarConsumed = jarConsumed(graph);

        long forecastStart = Perf.start();
        Forecast forecast = forecast(request, listener, graph, units, moduleDirs);
        Perf.end(
                "ws-forecast(hint="
                        + (request.dirtyHint() != null)
                        + ",dirty="
                        + forecast.dirty().size()
                        + ",restore="
                        + forecast.restoreNeeded().size()
                        + ")",
                forecastStart);

        return new Ready(new Context(
                request,
                entryBuild,
                graph,
                List.copyOf(units),
                immutableOrderedSet(moduleDirs),
                immutableOrderedSet(jarConsumed),
                scheduled(units, forecast.dirty(), forecast.restoreNeeded()),
                forecast.preflight()));
    }

    private static Optional<Completed> freshenLock(
            WorkspaceRequest request, JkBuild entryBuild, WorkspaceBuildListener listener, Clock clock) {
        if (!request.freshenLock()) return Optional.empty();
        Path rootLock = LockPaths.lockFile(request.entryDir());
        boolean lockStale = WorkspaceLock.workspaceLockStale(request.entryDir(), entryBuild, rootLock);
        if (lockStale) {
            long lockEta = WorkspaceLock.estimateLockMillis(request.entryDir(), request.cache());
            listener.onEtaEstimate(lockEta + EffortWeights.MS_PER_WEIGHT * 8L);
        }
        listener.onPreflight("lock", 0, 0, lockStale ? "Refreshing workspace lock…" : "Workspace lock ready");
        long started = clock.nanos();
        BuildService.LockGuard guard =
                WorkspaceLock.ensureWorkspaceLockFresh(request.entryDir(), request.cache(), lockStale);
        if (guard.status() != 0) {
            String error = guard.error() != null ? guard.error() : "dependency resolution failed";
            listener.onPreflightFailed("lock", (clock.nanos() - started) / 1_000_000L, error);
            return Optional.of(completed(false, guard.status(), List.of(error)));
        }
        listener.onPreflight("lock", 1, 1, "Workspace lock ready");
        return Optional.empty();
    }

    /**
     * The lock's first-party plugin rows follow the running jk ({@link FirstPartyPins}); a build
     * that moved any says so once, for the whole workspace. A lock the plugin path cannot read is
     * reported there, with its remedy.
     */
    private static void followFirstPartyPins(
            WorkspaceRequest request, List<BuildGraph.BuildUnit> units, WorkspaceBuildListener listener) {
        if (!request.freshenLock()) return;
        Set<String> declared = new HashSet<>();
        for (BuildGraph.BuildUnit unit : units) {
            for (PluginDeclaration declaration : unit.manifest().plugins()) declared.add(declaration.coordinate());
        }
        List<FirstPartyPins.Repin> moved;
        try {
            moved = FirstPartyPins.follow(request.entryDir(), declared);
        } catch (IOException | RuntimeException unreadable) {
            return;
        }
        if (!moved.isEmpty()) listener.onNote(FirstPartyPins.describe(moved));
    }

    private static Forecast forecast(
            WorkspaceRequest request,
            WorkspaceBuildListener listener,
            BuildGraph.Result graph,
            List<BuildGraph.BuildUnit> units,
            Set<Path> moduleDirs) {
        Set<Path> dirty;
        Set<Path> restoreNeeded = Set.of();
        Optional<BuildForecasting.Preflight> preflight = Optional.empty();
        if (request.dirtyHint() != null) {
            listener.onPreflight("checking", 0, 0, "Using dirty set…");
            dirty = ModuleHints.withPrereqs(graph, request.dirtyHint());
            listener.onPreflight(
                    "checking", 1, 1, dirty.isEmpty() ? "Nothing dirty" : dirty.size() + " module(s) dirty");
        } else if (request.testOnly()) {
            listener.onPreflight("checking", 0, 0, "Testing all selected modules…");
            dirty = Set.copyOf(moduleDirs);
            listener.onPreflight("checking", 1, 1, dirty.size() + " module(s)");
        } else {
            listener.onPreflight("checking", 0, 0, "Checking cache…");
            BuildForecasting.Preflight computed = BuildForecasting.forecastWithFingerprints(
                    graph,
                    request.cache(),
                    request.skipTests(),
                    request.entryDir(),
                    request.target(),
                    terminalTargetDirs(units, request),
                    true,
                    request.profile(),
                    request.spec() == null ? null : request.spec().m2Dir());
            preflight = Optional.of(computed);
            dirty = computed.dirty();
            restoreNeeded = computed.restoreNeeded();
            String message;
            if (dirty.isEmpty() && restoreNeeded.isEmpty()) message = "All modules up to date";
            else if (dirty.isEmpty()) message = restoreNeeded.size() + " module(s) restore";
            else if (restoreNeeded.isEmpty()) message = dirty.size() + " module(s) dirty";
            else message = dirty.size() + " dirty, " + restoreNeeded.size() + " restore";
            listener.onPreflight("checking", 1, 1, message);
        }
        return new Forecast(immutableOrderedSet(dirty), immutableOrderedSet(restoreNeeded), preflight);
    }

    /**
     * The modules this build runs: the dirty set plus every module whose inputs are clean but
     * whose outputs are missing, in graph order. A restore-needed module runs its own plan, and
     * each step restores by the action key it computes from its current inputs — the compile by
     * {@code JavaCompile}'s key, the jar by the package key — so what comes back is what this
     * build would produce. Nothing restores by "the last record for this task": after an edit, a
     * build, a revert and a wipe, that pointer names the edited build's outputs, and an empty tree
     * refilled from it ships the wrong bytecode under a hitting key. Every step of such a plan is a
     * cache hit, so this costs milliseconds; dropping these modules instead leaves every dependent
     * failing in resolve classpath with "sibling not compiled" — the shape a memo takes after any
     * failed build, once {@code target/} is gone.
     */
    static Set<Path> scheduled(List<BuildGraph.BuildUnit> units, Set<Path> dirty, Set<Path> restoreNeeded) {
        if (restoreNeeded.isEmpty()) return immutableOrderedSet(dirty);
        LinkedHashSet<Path> toBuild = new LinkedHashSet<>();
        for (BuildGraph.BuildUnit unit : units) {
            if (dirty.contains(unit.dir()) || restoreNeeded.contains(unit.dir())) toBuild.add(unit.dir());
        }
        // Paths the graph does not spell exactly as the sets do still get built.
        toBuild.addAll(dirty);
        toBuild.addAll(restoreNeeded);
        return Collections.unmodifiableSet(toBuild);
    }

    private static Set<Path> jarConsumed(BuildGraph.Result graph) {
        Set<Path> consumed = new LinkedHashSet<>();
        for (Set<Path> prereqs : graph.edges().values()) {
            for (Path prereq : prereqs) consumed.add(BuildGraph.canonicalPath(prereq));
        }
        return consumed;
    }

    static BuildGraph.Result applySelectionCone(BuildGraph.Result graph, WorkspaceRequest request) {
        WorkspaceSpec spec = request.spec();
        if (spec == null || !spec.hasSelection()) return graph;
        Map<Path, JkBuild> byDir = new LinkedHashMap<>();
        for (BuildGraph.BuildUnit unit : graph.topoOrder()) {
            byDir.put(unit.dir(), unit.manifest());
        }
        var scopes = request.skipTests() ? ModuleOrder.PRODUCTION_SCOPES : List.of(Scope.values());
        Set<Path> cone = WorkspaceCone.expand(byDir, spec.selectedModules(), scopes);
        return graph.restrict(cone);
    }

    /** Module dirs eligible for target-specific terminal work during forecast and prepare. */
    public static Set<Path> terminalTargetDirs(List<BuildGraph.BuildUnit> units, WorkspaceRequest request) {
        WorkspaceTarget target = request.target();
        WorkspaceSpec spec = request.spec() == null ? WorkspaceSpec.DEFAULT : request.spec();
        if (target == WorkspaceTarget.INSTALL || target == WorkspaceTarget.RESHELVE) {
            Set<Path> all = new LinkedHashSet<>();
            for (BuildGraph.BuildUnit unit : units) {
                if (!CompileSupport.coordinatorOnly(unit.manifest(), unit.dir())) all.add(unit.dir());
            }
            return all;
        }
        if (target != WorkspaceTarget.NATIVE && target != WorkspaceTarget.IMAGE) return Set.of();
        Set<Path> terminals = new LinkedHashSet<>();
        for (BuildGraph.BuildUnit unit : units) {
            Path dir = unit.dir();
            boolean selected = !spec.hasSelection()
                    || spec.selectedModules().stream()
                            .anyMatch(path -> BuildGraph.canonicalPath(path).equals(BuildGraph.canonicalPath(dir)));
            if (!selected) continue;
            if (target == WorkspaceTarget.NATIVE && GraalHomes.lookup(dir, spec.graalByDir()) == null) continue;
            terminals.add(dir);
        }
        return terminals;
    }

    private static Completed completed(boolean success, int exitCode, List<String> errors) {
        return new Completed(new WorkspaceResult(success, exitCode, List.of(), errors));
    }

    private static Set<Path> immutableOrderedSet(Iterable<Path> paths) {
        LinkedHashSet<Path> ordered = new LinkedHashSet<>();
        for (Path path : paths) ordered.add(path);
        return Collections.unmodifiableSet(ordered);
    }

    private record Forecast(Set<Path> dirty, Set<Path> restoreNeeded, Optional<BuildForecasting.Preflight> preflight) {}
}
