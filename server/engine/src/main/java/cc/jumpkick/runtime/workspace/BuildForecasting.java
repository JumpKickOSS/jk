// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Log;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.PreflightMemo;
import cc.jumpkick.runtime.TaskForecaster;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.wire.runtime.ExplainPlan;
import cc.jumpkick.wire.runtime.TaskForecast;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Preflight dirty-forecast and explain-plan assembly for {@link BuildService}.
 */
@NullMarked
public final class BuildForecasting {

    private BuildForecasting() {}

    /**
     * The set of module dirs the forecast predicts will do real work this build — used to reserve
     * their progress-bar slice up front. {@code --force} marks every module dirty; on any forecast
     * error, pessimistically returns all modules (so nothing is under-reserved).
     */
    public static Set<Path> forecastDirtyDirs(BuildGraph.Result graph, Path cache) {
        return forecastDirtyDirs(graph, cache, false);
    }

    /**
     * As {@link #forecastDirtyDirs(BuildGraph.Result, Path)} but honoring {@code skipTests}: a
     * {@code --skip-tests} build never runs the test steps, so their staleness must neither mark a
     * module dirty (it would force an engine build of a fully-cached workspace) nor cost the
     * test-stamp content hashing.
     */
    public static Set<Path> forecastDirtyDirs(BuildGraph.Result graph, Path cache, boolean skipTests) {
        return forecastDirtyDirs(graph, cache, skipTests, null);
    }

    /**
     * As {@link #forecastDirtyDirs(BuildGraph.Result, Path, boolean)} with optional {@code entryDir}
     * for the local preflight dirty memo. When {@code entryDir} is non-null and inputs are
     * unchanged, returns the memoized dirty set without a full {@link TaskForecaster} walk.
     */
    public static Set<Path> forecastDirtyDirs(
            BuildGraph.Result graph, Path cache, boolean skipTests, @Nullable Path entryDir) {
        return forecastWithFingerprints(graph, cache, skipTests, entryDir).dirty();
    }

    /**
     * Read-only variant for pure estimates (ForecastVerb, post-clean ETA): consults the memo but
     * NEVER stores one. A query that writes {@code target/.jk/preflight} resurrects the target
     * dir right after {@code jk clean --force} wiped it.
     */
    public static Set<Path> forecastDirtyDirsReadOnly(
            BuildGraph.Result graph, Path cache, boolean skipTests, Path entryDir) {
        return forecastWithFingerprints(graph, cache, skipTests, entryDir, WorkspaceTarget.PACKAGE, Set.of(), false)
                .dirty();
    }

    /**
     * A preflight verdict: input-dirty modules, modules needing output restore (inputs clean),
     * fingerprints for the dirty memo, optional {@link TaskForecast.Module} list when a full
     * forecast walk ran (reuse for ETA — do not walk twice), and per-module reasons for the dirty
     * modules the preflight itself scheduled — those whose inputs it could not read.
     */
    record Preflight(
            Set<Path> dirty,
            Set<Path> restoreNeeded,
            Map<Path, String> fingerprints,
            List<TaskForecast.Module> modules,
            Map<Path, String> reasons) {
        Preflight {
            dirty = dirty == null ? Set.of() : Set.copyOf(dirty);
            restoreNeeded = restoreNeeded == null ? Set.of() : Set.copyOf(restoreNeeded);
            fingerprints = fingerprints == null ? Map.of() : Map.copyOf(fingerprints);
            modules = modules == null ? List.of() : List.copyOf(modules);
            reasons = reasons == null ? Map.of() : Map.copyOf(reasons);
        }

        Preflight(
                Set<Path> dirty,
                Set<Path> restoreNeeded,
                Map<Path, String> fingerprints,
                List<TaskForecast.Module> modules) {
            this(dirty, restoreNeeded, fingerprints, modules, Map.of());
        }

        Preflight(Set<Path> dirty, Map<Path, String> fingerprints, List<TaskForecast.Module> modules) {
            this(dirty, Set.of(), fingerprints, modules, Map.of());
        }

        Preflight(Set<Path> dirty, Map<Path, String> fingerprints) {
            this(dirty, Set.of(), fingerprints, List.of(), Map.of());
        }
    }

    /** The explain text for a module the preflight scheduled: the fact, then the input it could not read. */
    static final String REBUILT_BECAUSE = "rebuilt because ";

    /**
     * As {@link #forecastDirtyDirs} but also returning the fingerprint snapshot taken BEFORE the
     * forecast walk — the only fingerprints a post-build {@link PreflightMemo#storeDirty} may use
     * (fingerprinting after the build records mid-build edits as clean).
     */
    static Preflight forecastWithFingerprints(
            BuildGraph.Result graph, Path cache, boolean skipTests, @Nullable Path entryDir) {
        return forecastWithFingerprints(graph, cache, skipTests, entryDir, WorkspaceTarget.PACKAGE, Set.of());
    }

    static Preflight forecastWithFingerprints(
            BuildGraph.Result graph, Path cache, boolean skipTests, @Nullable Path entryDir, WorkspaceTarget target) {
        return forecastWithFingerprints(graph, cache, skipTests, entryDir, target, Set.of());
    }

    /**
     * As above with {@code terminalDirs}: the resolved terminal module set for NATIVE/IMAGE
     * targets (see {@code TaskForecaster.of}) so the forecast schedules the same terminal steps
     * plan assembly will build.
     */
    static Preflight forecastWithFingerprints(
            BuildGraph.Result graph,
            Path cache,
            boolean skipTests,
            @Nullable Path entryDir,
            WorkspaceTarget target,
            Set<Path> terminalDirs) {
        return forecastWithFingerprints(graph, cache, skipTests, entryDir, target, terminalDirs, true, null);
    }

    /** {@code persistMemo=false}: consult but never store — read-only estimates. */
    static Preflight forecastWithFingerprints(
            BuildGraph.Result graph,
            Path cache,
            boolean skipTests,
            @Nullable Path entryDir,
            WorkspaceTarget target,
            Set<Path> terminalDirs,
            boolean persistMemo) {
        return forecastWithFingerprints(graph, cache, skipTests, entryDir, target, terminalDirs, persistMemo, null);
    }

    /**
     * As above under the request's {@code --profile}. The profile's javac args key every compile
     * step, so the walk forecasts against them and the dirty memo is stored and consulted under
     * the profile's name: a default build's clean memo says nothing about a profile build.
     */
    static Preflight forecastWithFingerprints(
            BuildGraph.Result graph,
            Path cache,
            boolean skipTests,
            @Nullable Path entryDir,
            WorkspaceTarget target,
            Set<Path> terminalDirs,
            boolean persistMemo,
            @Nullable String profile) {
        return forecastWithFingerprints(
                graph, cache, skipTests, entryDir, target, terminalDirs, persistMemo, profile, null);
    }

    /**
     * As above with the install request's {@code --m2-dir}, which the cache-install forecast reads
     * so that it and the step it predicts judge "already installed" against the same local repo.
     */
    static Preflight forecastWithFingerprints(
            BuildGraph.Result graph,
            Path cache,
            boolean skipTests,
            @Nullable Path entryDir,
            WorkspaceTarget target,
            Set<Path> terminalDirs,
            boolean persistMemo,
            @Nullable String profile,
            @Nullable Path m2Dir) {
        WorkspaceTarget t = target == null ? WorkspaceTarget.PACKAGE : target;
        // The dirty memo's clean claim covers package outputs only (it checks the module target
        // dir, not terminal artifacts). NATIVE/IMAGE/COMPILE/INSTALL must always run the
        // target-aware forecast walk — a memo hit here would skip a missing binary, a
        // never-skippable image push, or a cache-install into repos/jk-local. The memo is also
        // keyed without target, so a PACKAGE store must never be consumed by a terminal-target
        // run (jk build && jk install would no-op to success).
        // The memo is also keyed without the test selection: a widened run (`jk build --all`,
        // tag flags) must take the real forecast walk — its run-tests stamps differ from the
        // default tier the memo's clean claim covered.
        boolean defaultSelection = SessionContext.current().testSelection().equals(TestSelection.DEFAULT);
        boolean memoSafe = (t == WorkspaceTarget.PACKAGE || t == WorkspaceTarget.TEST) && defaultSelection;
        Set<Path> all = new HashSet<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) all.add(u.dir());
        // --force / --redo: every module runs — skip the expensive per-step forecast walk for dirty
        // detection; ETA still builds a plan via {@link #explainFromGraph} when needed.
        if (SessionContext.current().config().rebuildOr(false)
                || SessionContext.current().config().forceOr(false)) {
            return new Preflight(all, Map.of(), List.of());
        }
        Map<Path, String> fps;
        Map<Path, PreflightMemo.Uncertain> uncertain = Map.of();
        if (entryDir != null && memoSafe) {
            var memo = PreflightMemo.tryLoadDirty(entryDir, graph, skipTests, profile);
            if (memo.isPresent()) {
                Perf.note(
                        "preflight-memo hit",
                        "dirty",
                        memo.get().dirty().size(),
                        "restore",
                        memo.get().restoreNeeded().size(),
                        "restoreDirs",
                        memo.get().restoreNeeded());
                // Memo hit: inputs validated. Empty dirty+restore → skip TaskForecaster.
                // restoreNeeded alone → restore path (no full rebuild forecast).
                // Non-empty dirty still needs a forecast for ETA step lists; caller walks once.
                Set<Path> memoDirty = new HashSet<>(memo.get().dirty());
                withStaleOutputs(graph, entryDir, memo.get().fingerprints(), memoDirty);
                return new Preflight(
                        memoDirty, memo.get().restoreNeeded(), memo.get().fingerprints(), List.of());
            }
            PreflightMemo.Snapshot snapshot = PreflightMemo.snapshotFingerprints(graph, skipTests);
            fps = snapshot.fingerprints();
            uncertain = snapshot.uncertain();
        } else {
            fps = Map.of();
        }
        try {
            Cas cas = JkStores.storeCas(); // artifact CAS for classpath fingerprints
            ActionCache ac = new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache));
            List<TaskForecast.Module> modules = TaskForecaster.of(
                    graph,
                    cas,
                    ac,
                    cache,
                    skipTests,
                    t,
                    terminalDirs == null ? Set.of() : terminalDirs,
                    profile,
                    m2Dir);
            Set<Path> dirty = new HashSet<>();
            Set<Path> restoreNeeded = new HashSet<>();
            for (TaskForecast.Module m : modules) {
                if (!m.dirty()) continue;
                if (isRestoreOnly(m)) {
                    restoreNeeded.add(m.dir());
                } else {
                    dirty.add(m.dir());
                }
                if (Perf.enabled()) {
                    for (TaskForecast.Task p : m.steps()) {
                        if (!p.cached()) Perf.note("dirty " + m.coord() + " " + p.name(), "text", p.text());
                    }
                }
            }
            // The walk answers "is every step in the action cache?", which is not the same
            // question as "are the outputs on disk the ones those steps produce". They part
            // company whenever content returns to a state the cache has seen: every step is
            // cached, nothing is missing so nothing restores, and the artifacts are the previous
            // run's. See ModuleInputProvenance.
            withStaleOutputs(graph, entryDir, fps, dirty);
            // A module the preflight could not fingerprint is scheduled on that fact alone: no
            // memo can vouch for it, and the walk above may have priced it against inputs it
            // could not see either. Explain carries the reason; the build log says it once.
            Map<Path, String> reasons = new LinkedHashMap<>();
            for (var e : uncertain.entrySet()) {
                dirty.add(e.getKey());
                reasons.put(e.getKey(), REBUILT_BECAUSE + e.getValue().reason());
                Log.warn("jk: " + e.getKey().getFileName() + " " + REBUILT_BECAUSE
                        + e.getValue().reason());
            }
            if (entryDir != null && memoSafe && persistMemo) {
                // Store input-dirty only — restoreNeeded is re-derived from missing outputs on load.
                PreflightMemo.storeDirty(entryDir, graph, skipTests, profile, dirty, fps);
            }
            return new Preflight(dirty, restoreNeeded, fps, modules, reasons);
        } catch (RuntimeException e) {
            return new Preflight(all, Set.of(), fps, List.of());
        }
    }

    /**
     * Add any module whose recorded output provenance disagrees with its current inputs.
     *
     * <p>Conservative on purpose: a module with no record is left alone (see
     * {@link ModuleInputProvenance}), and a module already dirty is unaffected. Only a record that
     * positively names other inputs forces work, so the cost is one rebuild of a module that was
     * going to produce wrong bytes.
     */
    private static void withStaleOutputs(
            BuildGraph.Result graph, @Nullable Path entryDir, Map<Path, String> fingerprints, Set<Path> dirty) {
        if (graph == null || entryDir == null || fingerprints == null || fingerprints.isEmpty()) return;
        Path root = entryDir.toAbsolutePath().normalize();
        for (BuildGraph.BuildUnit unit : graph.topoOrder()) {
            Path dir = unit.dir().toAbsolutePath().normalize();
            if (dirty.contains(dir)) continue;
            String fp = fingerprints.get(dir);
            if (ModuleInputProvenance.outputsFromOtherInputs(root, dir, unit.manifest(), fp)) {
                dirty.add(dir);
            }
        }
    }

    /** True when the only material non-cached step is the synthetic restore gate. */
    static boolean isRestoreOnly(TaskForecast.Module m) {
        boolean sawRestore = false;
        for (TaskForecast.Task s : m.steps()) {
            if (s.cached() || TaskForecast.Module.isBookkeepingStep(s.name())) continue;
            if (!TaskForecast.Module.isMaterialWork(s.name())) continue;
            if (TaskNames.RESTORE_OUTPUTS.equals(s.name())) {
                sawRestore = true;
                continue;
            }
            return false;
        }
        return sawRestore;
    }

    /**
     * Forecast the build without running it: resolve the module graph and run the truthful
     * per-step {@link TaskForecaster} over it, returning an {@link ExplainPlan} the caller
     * renders. Pure policy — nothing here writes to {@code stdout}/{@code stderr}. Graph-resolution
     * errors come back in {@link ExplainPlan#errors} (the caller renders the same failure); an
     * {@link IOException} probing the workspace still propagates, exactly as the direct resolve did.
     */
    public static ExplainPlan explain(Path entryDir, JkBuild entryBuild, Path cache) throws IOException {
        return explain(entryDir, entryBuild, cache, false);
    }

    /**
     * As {@link #explain(Path, JkBuild, Path)} with {@code skipTests} matching {@code jk build
     * --skip-tests} / {@code jk explain --skip-tests} so the forecast does not claim test work the
     * live command will not run.
     */
    public static ExplainPlan explain(Path entryDir, JkBuild entryBuild, Path cache, boolean skipTests)
            throws IOException {
        BuildGraph.Result graph = BuildGraph.resolve(entryDir, entryBuild);
        return explainFromGraph(graph, cache, skipTests, entryDir);
    }

    /**
     * Forecast from an already-resolved graph — the same {@link TaskForecaster} walk {@code jk
     * build} uses for its countdown seed so explain and build never price different step sets.
     */
    public static ExplainPlan explainFromGraph(BuildGraph.Result graph, Path cache, boolean skipTests) {
        return explainFromGraph(graph, cache, skipTests, null);
    }

    /**
     * As {@link #explainFromGraph(BuildGraph.Result, Path, boolean)} with {@code entryDir} for the
     * dirty-memo fast path: when a prior build recorded an empty dirty set and sources are still
     * unchanged, skip the multi-second {@link TaskForecaster} walk (same shortcut as fully-cached
     * {@code jk build}). ETA is 0; the plan is "Fully Cached" for every module.
     */
    public static ExplainPlan explainFromGraph(
            BuildGraph.Result graph, Path cache, boolean skipTests, @Nullable Path entryDir) {
        return explainFromGraph(graph, cache, skipTests, entryDir, null);
    }

    /** As above under the request's {@code --profile}, which keys the compile steps and the memo. */
    public static ExplainPlan explainFromGraph(
            BuildGraph.Result graph, Path cache, boolean skipTests, @Nullable Path entryDir, @Nullable String profile) {
        if (graph.hasErrors()) {
            return new ExplainPlan(List.of(), Map.of(), 1, List.copyOf(graph.errors()));
        }
        // Same fully-cached shortcut as buildWorkspace: empty dirty memo ⇒ no TaskForecaster.
        if (entryDir != null
                && !SessionContext.current().config().rebuildOr(false)
                && !SessionContext.current().config().forceOr(false)) {
            var memo = PreflightMemo.tryLoadDirty(entryDir, graph, skipTests, profile);
            if (memo.isPresent() && memo.get().dirty().isEmpty()) {
                Perf.note(
                        "explain preflight-memo hit fully-cached",
                        "units",
                        graph.topoOrder().size());
                return fullyCachedExplainPlan(graph);
            }
        }
        // Which modules will `jk build` actually schedule? That is the question explain answers, so
        // it has to ask it the same way the build does — through the memo-aware preflight, read-only
        // so a query never resurrects `target/.jk/preflight` after `jk clean --force`.
        //
        // Asking it the other way is what made explain a forecast of `jk test`. A bare
        // TaskForecaster walk reports every module whose action-cache entries are missing, and
        // reinstalling worker/engine jars invalidates every module's run-tests stamp — so one edit
        // to `server/engine` read as 30 modules dirty and priced their suites, while `jk build`
        // scheduled 7 and never touched them. Both statements were true; only one is the plan for
        // the command the user is about to run.
        Preflight pf = forecastWithFingerprints(
                graph,
                cache,
                skipTests,
                entryDir,
                WorkspaceTarget.PACKAGE,
                Set.of(),
                /* persistMemo= */ false,
                profile);
        Set<Path> scheduled = new HashSet<>(pf.dirty());
        scheduled.addAll(pf.restoreNeeded());
        if (scheduled.isEmpty()) return fullyCachedExplainPlan(graph);
        List<TaskForecast.Module> modules = pf.modules();
        if (modules.isEmpty()) {
            // Memo hit, or --force/--redo: no walk happened, so the step lists come from one here.
            Cas cas = JkStores.storeCas(); // artifact CAS for classpath fingerprints
            ActionCache actionCache = new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache));
            modules = TaskForecaster.of(
                    graph, cas, actionCache, cache, skipTests, WorkspaceTarget.PACKAGE, Set.of(), profile);
        }
        return new ExplainPlan(
                withReasons(onlyScheduled(modules, scheduled), pf.reasons()),
                graph.edges(),
                graph.maxReadyWidth(),
                List.of());
    }

    /** Attach the preflight's own reasons to the modules it scheduled. */
    private static List<TaskForecast.Module> withReasons(List<TaskForecast.Module> modules, Map<Path, String> reasons) {
        if (reasons.isEmpty()) return modules;
        List<TaskForecast.Module> out = new ArrayList<>(modules.size());
        for (TaskForecast.Module m : modules) {
            String reason = reasons.get(m.dir().toAbsolutePath().normalize());
            out.add(reason == null ? m : m.withReason(reason));
        }
        return out;
    }

    /**
     * Report a module the build will not schedule as having no work: nothing is going to happen to
     * it, which is what a plan for this build should say. Its steps may well be stale — a later
     * {@code jk test} would run them — but naming that here prices a different command.
     */
    private static List<TaskForecast.Module> onlyScheduled(List<TaskForecast.Module> modules, Set<Path> scheduled) {
        List<TaskForecast.Module> out = new ArrayList<>(modules.size());
        for (TaskForecast.Module m : modules) {
            out.add(
                    scheduled.contains(m.dir())
                            ? m
                            : new TaskForecast.Module(m.dir(), m.coord(), List.of(), 0, 0, false, false));
        }
        return out;
    }

    /**
     * Lightweight fully-cached plan from graph identity only (no stamp/action-cache probing). Empty
     * step lists ⇒ {@link TaskForecast.Module#dirty()} is false for every module; ETA is 0.
     */
    static ExplainPlan fullyCachedExplainPlan(BuildGraph.Result graph) {
        List<TaskForecast.Module> modules = new ArrayList<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) {
            // Empty steps → not dirty. Counts stay 0 on this path (header still shows modules).
            modules.add(new TaskForecast.Module(u.dir(), u.coord(), List.of(), 0, 0, false, false));
        }
        return new ExplainPlan(modules, graph.edges(), graph.maxReadyWidth(), List.of());
    }

    /**
     * Restrict an explain plan to the client's module selection so the ETA seed prices exactly
     * the scheduled set. Edges are intersected with the selection; a hinted module
     * keeps its per-step cache verdicts, so a forecast-clean selected module contributes only
     * its cache-check cost — matching what scheduling will actually do.
     */
    static ExplainPlan restrictToSelection(ExplainPlan plan, Set<Path> selection) {
        List<TaskForecast.Module> kept = new ArrayList<>();
        for (TaskForecast.Module m : plan.modules()) {
            if (selection.contains(m.dir())) kept.add(m);
        }
        Map<Path, Set<Path>> edges = new LinkedHashMap<>();
        plan.edges().forEach((from, tos) -> {
            if (!selection.contains(from)) return;
            Set<Path> t = new LinkedHashSet<>(tos);
            t.retainAll(selection);
            edges.put(from, t);
        });
        return new ExplainPlan(kept, edges, plan.maxReadyWidth(), plan.errors());
    }
}
