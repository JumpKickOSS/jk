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
     * A preflight verdict: dirty set, fingerprints for the dirty memo, and optional
     * {@link TaskForecast.Module} list when a full forecast walk ran (reuse for ETA — do not walk
     * twice).
     */
    record Preflight(Set<Path> dirty, Map<Path, String> fingerprints, List<TaskForecast.Module> modules) {
        Preflight {
            modules = modules == null ? List.of() : List.copyOf(modules);
        }

        Preflight(Set<Path> dirty, Map<Path, String> fingerprints) {
            this(dirty, fingerprints, List.of());
        }
    }

    /**
     * As {@link #forecastDirtyDirs} but also returning the fingerprint snapshot taken BEFORE the
     * forecast walk — the only fingerprints a post-build {@link PreflightMemo#storeDirty} may use
     * (fingerprinting after the build records mid-build edits as clean).
     */
    static Preflight forecastWithFingerprints(BuildGraph.Result graph, Path cache, boolean skipTests, Path entryDir) {
        Set<Path> all = new HashSet<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) all.add(u.dir());
        // --force / --redo: every module runs — skip the expensive per-step forecast walk for dirty
        // detection; ETA still builds a plan via {@link #explainFromGraph} when needed.
        if (SessionContext.current().config().rebuildOr(false)
                || SessionContext.current().config().forceOr(false)) {
            return new Preflight(all, Map.of(), List.of());
        }
        Map<Path, String> fps;
        if (entryDir != null) {
            var memo = PreflightMemo.tryLoadDirty(entryDir, graph, skipTests);
            if (memo.isPresent()) {
                if (Perf.ENABLED) {
                    System.err.println("[jk-perf] preflight-memo hit dirty="
                            + memo.get().dirty().size());
                }
                // Memo hit with empty dirty: fully cached — no TaskForecaster walk (fast path).
                // Non-empty dirty still needs a forecast for ETA step lists; caller walks once.
                return new Preflight(memo.get().dirty(), memo.get().fingerprints(), List.of());
            }
            fps = PreflightMemo.snapshotFingerprints(graph, skipTests);
        } else {
            fps = Map.of();
        }
        try {
            Cas cas = JkStores.cas(cache); // artifact CAS for classpath fingerprints
            ActionCache ac = new ActionCache(JkStores.cacheCas(cache), cache.resolve("actions"));
            List<TaskForecast.Module> modules = TaskForecaster.of(graph, cas, ac, cache, skipTests);
            Set<Path> dirty = new HashSet<>();
            for (TaskForecast.Module m : modules) {
                if (m.dirty()) dirty.add(m.dir());
                if (Perf.ENABLED && m.dirty()) {
                    for (TaskForecast.Task p : m.steps()) {
                        if (!p.cached())
                            System.err.println("[jk-perf] dirty " + m.coord() + " " + p.name() + " (" + p.text() + ")");
                    }
                }
            }
            if (entryDir != null) {
                PreflightMemo.storeDirty(entryDir, graph, skipTests, dirty, fps);
            }
            return new Preflight(dirty, fps, modules);
        } catch (RuntimeException e) {
            return new Preflight(all, fps, List.of());
        }
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
     * the scheduled set (JK-1584). Edges are intersected with the selection; a hinted module
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
