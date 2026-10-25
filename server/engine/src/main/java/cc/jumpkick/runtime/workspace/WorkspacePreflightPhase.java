// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ModuleOrder;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceCone;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.model.Scope;
import cc.jumpkick.model.Variants;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.plugin.manifest.VariantApply;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.EffortWeights;
import cc.jumpkick.runtime.FirstPartyPins;
import cc.jumpkick.runtime.InstallPlans;
import cc.jumpkick.runtime.ModuleJar;
import cc.jumpkick.runtime.PlannerSupport;
import cc.jumpkick.runtime.PreflightMemo;
import cc.jumpkick.runtime.TaskForecaster;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.runtime.base.SiblingArtifacts;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.jspecify.annotations.Nullable;

/** Parses, locks, resolves, selects, syncs, forecasts, and restores before planning begins. */
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
            Set<Path> launchedAssemblies,
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

        List<String> selectionErrors = selectionErrors(units, request);
        if (!selectionErrors.isEmpty()) return completed(false, Exit.CONFIG, selectionErrors);

        Optional<List<String>> syncErrors = WorkspaceSyncPhase.run(request, listener, clock);
        if (syncErrors.isPresent()) return completed(false, Exit.FAILURE, syncErrors.orElseThrow());

        Set<Path> moduleDirs = new LinkedHashSet<>();
        for (BuildGraph.BuildUnit unit : units) moduleDirs.add(unit.dir());
        Set<Path> jarConsumed = jarConsumed(graph);

        long forecastStart = Perf.start();
        Forecast forecast = forecast(request, listener, graph, units, moduleDirs, jarConsumed);
        Perf.end(
                "ws-forecast(hint="
                        + (request.dirtyHint() != null)
                        + ",dirty="
                        + forecast.dirty().size()
                        + ",restore="
                        + forecast.restoreNeeded().size()
                        + ")",
                forecastStart);

        List<String> refused = installRefusals(request, units, forecast.preflight());
        if (!refused.isEmpty()) return completed(false, Exit.FAILURE, refused);

        return new Ready(new Context(
                request,
                entryBuild,
                graph,
                List.copyOf(units),
                immutableOrderedSet(moduleDirs),
                immutableOrderedSet(jarConsumed),
                launchedAssemblies(graph),
                scheduled(units, forecast.dirty(), forecast.restoreNeeded()),
                forecast.preflight()));
    }

    /**
     * What an install would refuse, said before anything builds: a selected module whose release
     * version is already installed and whose jar the forecast says this build rewrites. Empty when
     * the goal is not an install or the forecast did not price the jar step (a dirty hint, or a
     * memo hit that carries no per-step verdicts): the install step's own check still applies.
     */
    static List<String> installRefusals(
            WorkspaceRequest request,
            List<BuildGraph.BuildUnit> units,
            Optional<BuildForecasting.Preflight> preflight) {
        if (request.target() != WorkspaceTarget.INSTALL) return List.of();
        WorkspaceSpec spec = request.spec() == null ? WorkspaceSpec.DEFAULT : request.spec();
        Path m2Dir = spec.m2Dir() != null ? spec.m2Dir() : Path.of(System.getProperty("user.home", "."), ".m2");
        Map<Path, TaskForecast.Module> byDir = new LinkedHashMap<>();
        for (TaskForecast.Module m :
                preflight.map(BuildForecasting.Preflight::modules).orElse(List.of())) {
            byDir.put(BuildGraph.canonicalPath(m.dir()), m);
        }
        List<String> refused = new ArrayList<>();
        for (BuildGraph.BuildUnit unit : units) {
            Path dir = BuildGraph.canonicalPath(unit.dir());
            if (spec.hasSelection()
                    && spec.selectedModules().stream()
                            .noneMatch(sel -> BuildGraph.canonicalPath(sel).equals(dir))) {
                continue;
            }
            String packaging = ModuleJar.packagingStep(unit.manifest(), unit.dir());
            if (packaging == null) continue;
            String hidden =
                    InstallPlans.shadowedByRelease(unit.manifest(), unit.dir(), m2Dir, spec.installDestination());
            if (hidden != null) {
                refused.add(hidden);
                continue;
            }
            TaskForecast.Module module = byDir.get(dir);
            if (module == null) continue;
            boolean jarRebuilds = module.steps().stream().anyMatch(t -> t.name().equals(packaging) && !t.cached());
            if (!jarRebuilds) continue;
            String message =
                    InstallPlans.refusedBeforeBuild(unit.manifest(), unit.dir(), m2Dir, spec.installDestination());
            if (message != null) refused.add(message);
        }
        return refused;
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
            Set<Path> moduleDirs,
            Set<Path> jarConsumed) {
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
            WorkspaceTarget core = coreTarget(request.target());
            if (core == null) {
                dirty = Set.of();
            } else {
                BuildForecasting.Preflight computed = BuildForecasting.forecastWithFingerprints(
                        graph, request.cache(), request.skipTests(), request.entryDir(), core, true, request.profile());
                preflight = Optional.of(computed);
                dirty = computed.dirty();
                restoreNeeded = computed.restoreNeeded();
            }
            dirty = withRunningTails(dirty, units, request, moduleDirs, jarConsumed, launchedAssemblies(graph));
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

    static Set<Path> jarConsumed(BuildGraph.Result graph) {
        Set<Path> consumed = new LinkedHashSet<>();
        for (Set<Path> prereqs : graph.edges().values()) {
            for (Path prereq : prereqs) consumed.add(BuildGraph.canonicalPath(prereq));
        }
        return consumed;
    }

    /**
     * The engine modules a nested-engine suite in {@code graph} launches from their fat jar: a test
     * run packages that jar before the suite starts, so the suite runs the checkout's engine and
     * every library bundled in it.
     */
    static Set<Path> launchedAssemblies(BuildGraph.Result graph) {
        Map<Path, JkBuild> byDir = new LinkedHashMap<>();
        for (BuildGraph.BuildUnit unit : graph.topoOrder()) {
            byDir.put(BuildGraph.canonicalPath(unit.dir()), unit.manifest());
        }
        Set<Path> launched = new LinkedHashSet<>();
        for (Map.Entry<Path, Set<Path>> edge : graph.edges().entrySet()) {
            JkBuild consumer = byDir.get(BuildGraph.canonicalPath(edge.getKey()));
            if (consumer == null || !PlannerSupport.needsNestedEngineIsolation(consumer)) continue;
            for (Path prereq : edge.getValue()) {
                Path dir = BuildGraph.canonicalPath(prereq);
                JkBuild module = byDir.get(dir);
                if (module != null && module.assembly() && PlannerSupport.isEngineModule(module)) launched.add(dir);
            }
        }
        return Collections.unmodifiableSet(launched);
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

    /**
     * The goal-agnostic lifecycle a goal's forecast prices: through COMPILE, TEST or PACKAGE. Null
     * for RESHELVE, which is all tail. Every goal past PACKAGE prices the same lifecycle as {@code
     * jk build}, so they agree on which modules are dirty; their tails are priced on their own.
     */
    static @Nullable WorkspaceTarget coreTarget(WorkspaceTarget target) {
        return switch (target) {
            case COMPILE, TEST -> target;
            case RESHELVE -> null;
            default -> WorkspaceTarget.PACKAGE;
        };
    }

    /**
     * {@code dirty} plus every module a step of its goal's tail runs in: an install whose bytes are
     * not shelved, a native image whose record misses, a publish or an image push, which always
     * run. Priced from the module's own plan, so a tail step the plan does not hold costs nothing.
     */
    static Set<Path> withRunningTails(
            Set<Path> dirty,
            List<BuildGraph.BuildUnit> units,
            WorkspaceRequest request,
            Set<Path> moduleDirs,
            Set<Path> jarConsumed,
            Set<Path> launchedAssemblies) {
        WorkspaceTarget target = request.target();
        if (target == coreTarget(target)) return dirty;
        ActionCache actionCache =
                new ActionCache(JkStores.cacheCas(request.cache()), CacheTree.ACTIONS.under(request.cache()));
        LinkedHashSet<Path> all = new LinkedHashSet<>(dirty);
        for (BuildGraph.BuildUnit unit : units) {
            if (all.contains(unit.dir())) continue;
            ModulePipeline.Planned planned = ModulePipeline.planned(
                    unit, request, moduleDirs, false, jarConsumed, launchedAssemblies, SiblingArtifacts.NONE);
            for (Task step : planned.plan().steps()) {
                if (!planned.tailSteps().contains(step.name())) continue;
                TaskForecast.Task priced;
                try {
                    priced = TaskForecaster.priceTailStep(step, unit, request.cache(), actionCache);
                } catch (IllegalStateException undeclared) {
                    throw undeclared;
                } catch (Exception e) {
                    // A tail step that cannot be priced runs: the pessimistic answer, never a skip.
                    Log.debug("tail forecast " + unit.coord() + " " + step.name(), e);
                    all.add(unit.dir());
                    break;
                }
                if (priced != null && !priced.cached()) {
                    all.add(unit.dir());
                    break;
                }
            }
        }
        return all;
    }

    /**
     * Every module whose {@code [variants]} the request's selection cannot build, named, before any
     * module's work starts: a module's own parse step would find it only once its upstream had built.
     */
    static List<String> selectionErrors(List<BuildGraph.BuildUnit> units, WorkspaceRequest request) {
        Variants.Selection selection = VariantApply.selection(request.variant(), SessionContext.current());
        List<String> errors = new ArrayList<>();
        for (BuildGraph.BuildUnit unit : units) {
            String error = VariantApply.selectionError(unit.manifest(), selection);
            if (error != null) errors.add(unit.coord() + ": " + error);
        }
        return errors;
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
