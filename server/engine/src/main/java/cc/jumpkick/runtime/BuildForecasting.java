// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.ActionCache;
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
    public static Set<Path> forecastDirtyDirs(BuildGraph.Result graph, Path cache, boolean skipTests, Path entryDir) {
        return forecastWithFingerprints(graph, cache, skipTests, entryDir).dirty();
    }

    /**
     * Read-only variant for pure estimates (ForecastVerb, post-clean ETA): consults the memo but
     * NEVER stores one. A query that writes {@code target/.jk/preflight} resurrects the target
     * dir right after {@code jk clean --force} wiped it (JK-2205).
     */
    public static Set<Path> forecastDirtyDirsReadOnly(
            BuildGraph.Result graph, Path cache, boolean skipTests, Path entryDir) {
        return forecastWithFingerprints(graph, cache, skipTests, entryDir, WorkspaceTarget.PACKAGE, Set.of(), false)
                .dirty();
    }

    /**
     * A preflight verdict: input-dirty modules, modules needing output restore (inputs clean),
     * fingerprints for the dirty memo, and optional {@link TaskForecast.Module} list when a full
     * forecast walk ran (reuse for ETA — do not walk twice).
     */
    record Preflight(
            Set<Path> dirty,
            Set<Path> restoreNeeded,
            Map<Path, String> fingerprints,
            List<TaskForecast.Module> modules) {
        Preflight {
            dirty = dirty == null ? Set.of() : Set.copyOf(dirty);
            restoreNeeded = restoreNeeded == null ? Set.of() : Set.copyOf(restoreNeeded);
            fingerprints = fingerprints == null ? Map.of() : Map.copyOf(fingerprints);
            modules = modules == null ? List.of() : List.copyOf(modules);
        }

        Preflight(Set<Path> dirty, Map<Path, String> fingerprints, List<TaskForecast.Module> modules) {
            this(dirty, Set.of(), fingerprints, modules);
        }

        Preflight(Set<Path> dirty, Map<Path, String> fingerprints) {
            this(dirty, Set.of(), fingerprints, List.of());
        }
    }

    /**
     * As {@link #forecastDirtyDirs} but also returning the fingerprint snapshot taken BEFORE the
     * forecast walk — the only fingerprints a post-build {@link PreflightMemo#storeDirty} may use
     * (fingerprinting after the build records mid-build edits as clean).
     */
    static Preflight forecastWithFingerprints(BuildGraph.Result graph, Path cache, boolean skipTests, Path entryDir) {
        return forecastWithFingerprints(graph, cache, skipTests, entryDir, WorkspaceTarget.PACKAGE, Set.of());
    }

    static Preflight forecastWithFingerprints(
            BuildGraph.Result graph, Path cache, boolean skipTests, Path entryDir, WorkspaceTarget target) {
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
            Path entryDir,
            WorkspaceTarget target,
            Set<Path> terminalDirs) {
        return forecastWithFingerprints(graph, cache, skipTests, entryDir, target, terminalDirs, true);
    }

    /** {@code persistMemo=false}: consult but never store — read-only estimates (JK-2205). */
    static Preflight forecastWithFingerprints(
            BuildGraph.Result graph,
            Path cache,
            boolean skipTests,
            Path entryDir,
            WorkspaceTarget target,
            Set<Path> terminalDirs,
            boolean persistMemo) {
        WorkspaceTarget t = target == null ? WorkspaceTarget.PACKAGE : target;
        // The dirty memo's clean claim covers package outputs only (it checks the module target
        // dir, not terminal artifacts). NATIVE/IMAGE/COMPILE must always run the target-aware
        // forecast walk — a memo hit here would skip a missing binary or a never-skippable
        // image push. The memo is also keyed without target, so a PACKAGE store must never be
        // consumed by a terminal-target run (jk build && jk native would no-op to success).
        // The memo is also keyed without the test selection: a widened run (`jk build --all`,
        // tag flags) must take the real forecast walk — its run-tests stamps differ from the
        // default tier the memo's clean claim covered (JK-2203).
        boolean defaultSelection = SessionContext.current()
                .testSelection()
                .equals(cc.jumpkick.config.TestSelection.DEFAULT);
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
        if (entryDir != null && memoSafe) {
            var memo = PreflightMemo.tryLoadDirty(entryDir, graph, skipTests);
            if (memo.isPresent()) {
                if (Perf.ENABLED) {
                    System.err.println("[jk-perf] preflight-memo hit dirty="
                            + memo.get().dirty().size()
                            + " restore="
                            + memo.get().restoreNeeded().size());
                }
                // Memo hit: inputs validated. Empty dirty+restore → skip TaskForecaster.
                // restoreNeeded alone → restore path (no full rebuild forecast).
                // Non-empty dirty still needs a forecast for ETA step lists; caller walks once.
                return new Preflight(
                        memo.get().dirty(),
                        memo.get().restoreNeeded(),
                        memo.get().fingerprints(),
                        List.of());
            }
            fps = PreflightMemo.snapshotFingerprints(graph, skipTests);
        } else {
            fps = Map.of();
        }
        try {
            Cas cas = JkStores.cas(cache); // artifact CAS for classpath fingerprints
            ActionCache ac = new ActionCache(JkStores.cacheCas(cache), cache.resolve("actions"));
            List<TaskForecast.Module> modules = TaskForecaster.of(
                    graph, cas, ac, cache, skipTests, t, terminalDirs == null ? Set.of() : terminalDirs);
            Set<Path> dirty = new HashSet<>();
            Set<Path> restoreNeeded = new HashSet<>();
            for (TaskForecast.Module m : modules) {
                if (!m.dirty()) continue;
                if (isRestoreOnly(m)) {
                    restoreNeeded.add(m.dir());
                } else {
                    dirty.add(m.dir());
                }
                if (Perf.ENABLED) {
                    for (TaskForecast.Task p : m.steps()) {
                        if (!p.cached())
                            System.err.println("[jk-perf] dirty " + m.coord() + " " + p.name() + " (" + p.text() + ")");
                    }
                }
            }
            if (entryDir != null && memoSafe && persistMemo) {
                // Store input-dirty only — restoreNeeded is re-derived from missing outputs on load.
                PreflightMemo.storeDirty(entryDir, graph, skipTests, dirty, fps);
            }
            return new Preflight(dirty, restoreNeeded, fps, modules);
        } catch (RuntimeException e) {
            return new Preflight(all, Set.of(), fps, List.of());
        }
    }

    /** True when the only material non-cached step is the synthetic restore gate. */
    static boolean isRestoreOnly(TaskForecast.Module m) {
        boolean sawRestore = false;
        for (TaskForecast.Task s : m.steps()) {
            if (s.cached() || TaskForecast.Module.isBookkeepingStep(s.name())) continue;
            if (!TaskForecast.Module.isMaterialWork(s.name())) continue;
            if ("restore-outputs".equals(s.name())) {
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
    public static ExplainPlan explainFromGraph(BuildGraph.Result graph, Path cache, boolean skipTests, Path entryDir) {
        if (graph.hasErrors()) {
            return new ExplainPlan(List.of(), Map.of(), 1, List.copyOf(graph.errors()));
        }
        // Same fully-cached shortcut as buildWorkspace: empty dirty memo ⇒ no TaskForecaster.
        if (entryDir != null
                && !SessionContext.current().config().rebuildOr(false)
                && !SessionContext.current().config().forceOr(false)) {
            var memo = PreflightMemo.tryLoadDirty(entryDir, graph, skipTests);
            if (memo.isPresent() && memo.get().dirty().isEmpty()) {
                if (Perf.ENABLED) {
                    System.err.println("[jk-perf] explain preflight-memo hit fully-cached units="
                            + graph.topoOrder().size());
                }
                return fullyCachedExplainPlan(graph);
            }
        }
        Cas cas = JkStores.cas(cache); // artifact CAS for classpath fingerprints
        ActionCache actionCache = new ActionCache(JkStores.cacheCas(cache), cache.resolve("actions"));
        List<TaskForecast.Module> modules = TaskForecaster.of(graph, cas, actionCache, cache, skipTests);
        return new ExplainPlan(modules, graph.edges(), graph.maxReadyWidth(), List.of());
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
