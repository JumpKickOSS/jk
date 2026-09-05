// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.wire.runtime.ExplainPlan;
import cc.jumpkick.wire.runtime.ModuleWorkCost;
import cc.jumpkick.wire.runtime.TaskForecast;
import cc.jumpkick.wire.runtime.WorkModel;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Engine-side build facade for any front-end. Pure orchestration — nothing writes stdout/stderr;
 * results are returned for the caller's view layer.
 *
 * <p>Lock-guard, execute, and fold live in {@link WorkspaceLock}, {@link WorkspaceExecute}, and
 * {@link BuildEta}/{@link BuildForecasting}. This type is the stable call surface.
 */
@NullMarked
public final class BuildService {

    private BuildService() {}

    /**
     * Outcome of the pre-build workspace lock-freshness guard.
     *
     * @param status process exit code — {@code 0} means the lock is fresh (or was re-locked OK)
     * @param error a bare message to surface (no command prefix), or {@code null}
     */
    public record LockGuard(int status, @Nullable String error) {
        public static final LockGuard OK = new LockGuard(0, null);
    }

    /**
     * Ensure the workspace lock reflects its manifests before a build: if the root {@code jk-lock.toml} is
     * absent, older than the root {@code jk.toml}, or older than any declared member manifest, re-run
     * the {@link LockFlow lock plan}. Soft failures (I/O, network) don't block the build — the
     * per-module path surfaces genuine problems when it resolves classpaths.
     */
    public static LockGuard ensureWorkspaceLockFresh(Path root, JkBuild rootBuild, Path cache) {
        return WorkspaceLock.ensureWorkspaceLockFresh(root, rootBuild, cache);
    }

    /**
     * As {@link #ensureWorkspaceLockFresh(Path, JkBuild, Path)} with the staleness answer already
     * computed — callers that just priced the re-lock for the ETA pass it in instead of
     * re-hashing every manifest.
     */
    public static LockGuard ensureWorkspaceLockFresh(Path root, Path cache, boolean stale) {
        return WorkspaceLock.ensureWorkspaceLockFresh(root, cache, stale);
    }

    /** Remaining-work estimate for a workspace re-lock (ms). */
    static long estimateLockMillis(Path entryDir, Path cache) {
        return WorkspaceLock.estimateLockMillis(entryDir, cache);
    }

    /**
     * True when {@code rootLock} is absent or older than the root manifest or any declared member
     * manifest — i.e. the merged workspace lock no longer reflects the manifests it was derived from.
     */
    public static boolean workspaceLockStale(Path root, JkBuild rootBuild, Path rootLock) {
        return WorkspaceLock.workspaceLockStale(root, rootBuild, rootLock);
    }

    /**
     * Compute the {@code src → <wsRoot>/target/<name>} hard-link map for a workspace build: every
     * application module's final artifacts (jar / shadow / native binary+library / OCI tar) are
     * surfaced under the workspace root's {@code target/}. On a filename collision across modules the
     * link name is prefixed with the module's group. Pure — {@link #linkModuleArtifacts} applies it.
     */
    public static Map<Path, Path> computeWorkspaceLinks(Iterable<Path> moduleDirs, Path workspaceRoot) {
        return WorkspaceArtifacts.computeLinks(moduleDirs, workspaceRoot);
    }

    /**
     * The set of module dirs the forecast predicts will do real work this build — used to reserve
     * their progress-bar slice up front. {@code --force} marks every module dirty; on any forecast
     * error, pessimistically returns all modules (so nothing is under-reserved).
     */
    public static Set<Path> forecastDirtyDirs(BuildGraph.Result graph, Path cache) {
        return BuildForecasting.forecastDirtyDirs(graph, cache);
    }

    /**
     * As {@link #forecastDirtyDirs(BuildGraph.Result, Path)} but honoring {@code skipTests}: a
     * {@code --skip-tests} build never runs the test steps, so their staleness must neither mark a
     * module dirty (it would force an engine build of a fully-cached workspace) nor cost the
     * test-stamp content hashing.
     */
    public static Set<Path> forecastDirtyDirs(BuildGraph.Result graph, Path cache, boolean skipTests) {
        return BuildForecasting.forecastDirtyDirs(graph, cache, skipTests);
    }

    /**
     * As {@link #forecastDirtyDirs(BuildGraph.Result, Path, boolean)} with optional {@code entryDir}
     * for the local preflight dirty memo. When {@code entryDir} is non-null and inputs are
     * unchanged, returns the memoized dirty set without a full {@link TaskForecaster} walk.
     */
    public static Set<Path> forecastDirtyDirs(
            BuildGraph.Result graph, Path cache, boolean skipTests, @Nullable Path entryDir) {
        return BuildForecasting.forecastDirtyDirs(graph, cache, skipTests, entryDir);
    }

    /** Read-only estimate: consults the preflight memo but never writes one. */
    public static Set<Path> forecastDirtyDirsReadOnly(
            BuildGraph.Result graph, Path cache, boolean skipTests, Path entryDir) {
        return BuildForecasting.forecastDirtyDirsReadOnly(graph, cache, skipTests, entryDir);
    }

    /**
     * Forecast the build without running it: resolve the module graph and run the truthful
     * per-step {@link TaskForecaster} over it, returning an {@link ExplainPlan} the caller
     * renders. Pure policy — nothing here writes to {@code stdout}/{@code stderr}. Graph-resolution
     * errors come back in {@link ExplainPlan#errors} (the caller renders the same failure); an
     * {@link IOException} probing the workspace still propagates, exactly as the direct resolve did.
     */
    public static ExplainPlan explain(Path entryDir, JkBuild entryBuild, Path cache) throws IOException {
        return BuildForecasting.explain(entryDir, entryBuild, cache);
    }

    /**
     * As {@link #explain(Path, JkBuild, Path)} with {@code skipTests} matching {@code jk build
     * --skip-tests} / {@code jk explain --skip-tests} so the forecast does not claim test work the
     * live command will not run.
     */
    public static ExplainPlan explain(Path entryDir, JkBuild entryBuild, Path cache, boolean skipTests)
            throws IOException {
        return BuildForecasting.explain(entryDir, entryBuild, cache, skipTests);
    }

    /**
     * Forecast from an already-resolved graph — the same {@link TaskForecaster} walk {@code jk
     * build} uses for its countdown seed so explain and build never price different step sets.
     */
    public static ExplainPlan explainFromGraph(BuildGraph.Result graph, Path cache, boolean skipTests) {
        return BuildForecasting.explainFromGraph(graph, cache, skipTests);
    }

    /**
     * As {@link #explainFromGraph(BuildGraph.Result, Path, boolean)} with {@code entryDir} for the
     * dirty-memo fast path: when a prior build recorded an empty dirty set and sources are still
     * unchanged, skip the multi-second {@link TaskForecaster} walk (same shortcut as fully-cached
     * {@code jk build}). ETA is 0; the plan is "Fully Cached" for every module.
     */
    public static ExplainPlan explainFromGraph(BuildGraph.Result graph, Path cache, boolean skipTests, Path entryDir) {
        return BuildForecasting.explainFromGraph(graph, cache, skipTests, entryDir);
    }

    /**
     * Lightweight fully-cached plan from graph identity only (no stamp/action-cache probing). Empty
     * step lists ⇒ {@link TaskForecast.Module#dirty()} is false for every module; ETA is 0.
     */
    static ExplainPlan fullyCachedExplainPlan(BuildGraph.Result graph) {
        return BuildForecasting.fullyCachedExplainPlan(graph);
    }

    /**
     * Predicted wall-clock for building {@code plan}, in millis ({@code 0} = nothing to do / fully
     * cached).
     *
     * <p><b>Hard invariant:</b> this is the <em>only</em> ETA seed used by both {@code jk explain}
     * and {@code jk build}'s countdown. Same forecast plan, same {@code workers}/{@code
     * maxModuleConcurrency}/{@code parallelTests}, same {@link BuildEta} seed — the numbers must
     * match bit-for-bit for a given workspace and session. See {@code docs/perf/progress-contract.md}.
     *
     * @param workers within-module test JVMs; {@code 0} = auto (identical to bare {@code jk build})
     * @param maxModuleConcurrency module-concurrency cap from {@code -j} / jobs (same as workspace
     *     build); {@code ≤ 0} means clamp only to available processors
     */
    public static long estimateEtaMillis(
            ExplainPlan plan,
            Path entryDir,
            Path cache,
            int workers,
            @Nullable Path jdksDir,
            @Nullable String profile,
            boolean skipTests,
            boolean verbose,
            boolean parallelTests,
            int maxModuleConcurrency) {
        return BuildEta.estimateEtaMillis(
                plan,
                entryDir,
                cache,
                workers,
                jdksDir,
                profile,
                skipTests,
                verbose,
                parallelTests,
                maxModuleConcurrency);
    }

    /**
     * ETA seed plus the cost assembly it was computed from — the single assembly both the
     * estimate and the {@link WorkModel} consume.
     */
    public record EtaModel(
            long etaMs,
            List<EffortWeights.ModuleCost> costs,
            int concurrency,
            boolean serial,
            /** The schedule simulation BEFORE the learned {@link ScheduleBias} — what bias observations compare against. */
            long rawScheduleMs) {
        public EtaModel(long etaMs, List<EffortWeights.ModuleCost> costs, int concurrency, boolean serial) {
            this(etaMs, costs, concurrency, serial, 0);
        }

        static EtaModel empty() {
            return new EtaModel(0, List.of(), 1, true);
        }
    }

    public static EtaModel estimateEtaModel(
            ExplainPlan plan,
            Path entryDir,
            Path cache,
            int workers,
            Path jdksDir,
            String profile,
            boolean skipTests,
            boolean verbose,
            boolean parallelTests,
            int maxModuleConcurrency) {
        return BuildEta.estimateEtaModel(
                plan,
                entryDir,
                cache,
                workers,
                jdksDir,
                profile,
                skipTests,
                verbose,
                parallelTests,
                maxModuleConcurrency);
    }

    /**
     * Backward-compatible overload: {@code serial=true} → {@code maxModuleConcurrency=1}; otherwise
     * no jobs clamp. Prefer the overload that takes {@code maxModuleConcurrency} so explain and
     * build pass the same {@code -j} value.
     */
    public static long estimateEtaMillis(
            ExplainPlan plan,
            Path entryDir,
            Path cache,
            int workers,
            @Nullable Path jdksDir,
            @Nullable String profile,
            boolean skipTests,
            boolean verbose,
            boolean serial,
            boolean parallelTests) {
        return BuildEta.estimateEtaMillis(
                plan, entryDir, cache, workers, jdksDir, profile, skipTests, verbose, serial, parallelTests);
    }

    /**
     * Module-concurrency budget for the ETA schedule — <b>must</b> match {@link
     * #buildWorkspace}'s {@code concurrency} so explain and the live countdown clamp the same way.
     */
    static int etaConcurrency(int maxReadyWidth, int workers, boolean parallelTests, int maxModuleConcurrency) {
        return BuildEta.etaConcurrency(maxReadyWidth, workers, parallelTests, maxModuleConcurrency);
    }

    /**
     * Restrict an explain plan to the client's module selection so the ETA seed prices exactly
     * the scheduled set. Edges are intersected with the selection; a hinted module
     * keeps its per-step cache verdicts, so a forecast-clean selected module contributes only
     * its cache-check cost — matching what scheduling will actually do.
     */
    static ExplainPlan restrictToSelection(ExplainPlan plan, Set<Path> selection) {
        return BuildForecasting.restrictToSelection(plan, selection);
    }

    /**
     * Order module costs in the same sequence as {@code units} (workspace topo / dirty list) so
     * first-ready schedule admission matches {@link WorkspaceScheduler}.
     */
    static List<ModuleWorkCost> orderCostsLikeUnits(
            List<BuildGraph.BuildUnit> units, List<EffortWeights.ModuleCost> costs) {
        return BuildEta.orderCostsLikeUnits(units, costs);
    }

    static List<EffortWeights.ModuleCost> etaCostsFromExplainPlan(
            ExplainPlan plan,
            Path cache,
            int workers,
            Path jdksDir,
            String profile,
            boolean skipTests,
            boolean verbose,
            int maxModuleConcurrency) {
        return BuildEta.etaCostsFromExplainPlan(
                plan, cache, workers, jdksDir, profile, skipTests, verbose, maxModuleConcurrency);
    }

    /**
     * Steps that should not contribute full historical walls to open-loop ETA. Cascade-forced
     * compile/package/native and resource-only producers almost always action-cache hit for
     * compile/test; billing suite walls for them was the multi-minute dogfood miss.
     */
    static boolean shouldDiscountCascadeStep(
            TaskForecast.Task s,
            boolean localCompile,
            boolean resourceDrift,
            boolean keepFullTests,
            boolean testResourceDrift) {
        return BuildEta.shouldDiscountCascadeStep(s, localCompile, resourceDrift, keepFullTests, testResourceDrift);
    }

    static boolean hasLocalCompileContent(TaskForecast.Module m) {
        return BuildEta.hasLocalCompileContent(m);
    }

    static boolean hasLocalContentWork(TaskForecast.Module m) {
        return BuildEta.hasLocalContentWork(m);
    }

    static boolean hasResourceDriftWork(TaskForecast.Module m) {
        return BuildEta.hasResourceDriftWork(m);
    }

    static boolean hasTestResourceDriftWork(TaskForecast.Module m) {
        return BuildEta.hasTestResourceDriftWork(m);
    }

    static boolean hasHeavyPackagingTail(TaskForecast.Module m) {
        return BuildEta.hasHeavyPackagingTail(m);
    }

    static boolean isCascadeForcedStep(TaskForecast.Task s) {
        return BuildEta.isCascadeForcedStep(s);
    }

    static boolean isCompileStepName(String name) {
        return BuildEta.isCompileStepName(name);
    }

    static boolean isCompileOrPackageStep(String name) {
        return BuildEta.isCompileOrPackageStep(name);
    }

    /**
     * An opaque, front-end-safe handle to a resolved build graph: enough for a caller to branch on
     * resolution errors / an empty workspace and then forecast dirty modules, without ever naming
     * {@link BuildGraph}/{@link BuildGraph.BuildUnit}. The engine-internal {@link BuildGraph.Result}
     * is reachable only through the package-private {@link #graph} accessor (feeding {@link
     * #forecastDirtyDirs(ResolvedGraph, Path)}), so the boundary is compiler-enforced.
     *
     * <p>A {@code final class} rather than a {@code record} precisely so {@code graph} can drop
     * below {@code public}.
     */
    public static final class ResolvedGraph {
        private final BuildGraph.Result graph;

        private ResolvedGraph(BuildGraph.Result graph) {
            this.graph = graph;
        }

        /** Engine-internal graph — package-private so front-ends can't reach {@link BuildGraph.Result}. */
        BuildGraph.Result graph() {
            return graph;
        }

        public boolean hasErrors() {
            return graph.hasErrors();
        }

        public List<String> errors() {
            return List.copyOf(graph.errors());
        }

        /** True when the graph resolved to zero build units (a workspace that declares no modules). */
        public boolean isEmpty() {
            return graph.topoOrder().isEmpty();
        }

        /** The build units in dependency-first order, as plain directory paths. */
        public List<Path> moduleDirs() {
            List<Path> dirs = new ArrayList<>();
            for (BuildGraph.BuildUnit u : graph.topoOrder()) dirs.add(u.dir());
            return dirs;
        }
    }

    /** Resolve the module graph rooted at {@code entryDir}, wrapped so front-ends never name {@link BuildGraph}. */
    public static ResolvedGraph resolveGraph(Path entryDir, JkBuild entryBuild) throws IOException {
        return new ResolvedGraph(BuildGraph.resolve(entryDir, entryBuild));
    }

    /** {@link #forecastDirtyDirs(BuildGraph.Result, Path)} over a front-end-held {@link ResolvedGraph}. */
    public static Set<Path> forecastDirtyDirs(ResolvedGraph graph, Path cache) {
        return forecastDirtyDirs(graph.graph(), cache);
    }

    /** {@link #forecastDirtyDirs(BuildGraph.Result, Path, boolean)} over a front-end-held {@link ResolvedGraph}. */
    public static Set<Path> forecastDirtyDirs(ResolvedGraph graph, Path cache, boolean skipTests) {
        return forecastDirtyDirs(graph.graph(), cache, skipTests, null);
    }

    /** As {@link #forecastDirtyDirs(ResolvedGraph, Path, boolean)} with preflight memo root. */
    public static Set<Path> forecastDirtyDirs(
            ResolvedGraph graph, Path cache, boolean skipTests, @Nullable Path entryDir) {
        return forecastDirtyDirs(graph.graph(), cache, skipTests, entryDir);
    }

    /** Read-only estimate over a front-end-held graph: never writes the memo. */
    public static Set<Path> forecastDirtyDirsReadOnly(
            ResolvedGraph graph, Path cache, boolean skipTests, Path entryDir) {
        return forecastDirtyDirsReadOnly(graph.graph(), cache, skipTests, entryDir);
    }

    /**
     * Build a whole workspace: resolve the module graph, size the worker-JVM memory plan (unless
     * {@link WorkspaceRequest#applyMemoryPlan} is {@code false} — see its javadoc), assemble each
     * module's plan, then schedule them in dependency order (each level concurrent) — running every
     * module's plan and surfacing artifacts under the workspace {@code target/}. Progress flows to
     * {@code listener}; the returned {@link WorkspaceResult} is the aggregate outcome. Pure of
     * presentation — the caller renders from the events.
     *
     * <p>This method does not assume it is the only in-flight caller in the process: memory planning
     * is opt-out precisely so a host running several concurrent builds in one JVM (a resident engine)
     * can plan once for its own concurrency instead of letting each call overwrite the shared
     * {@code HeapPlan}/{@code PluginSlots} state sized for just itself.
     */
    public static WorkspaceResult buildWorkspace(WorkspaceRequest req, WorkspaceBuildListener listener) {
        return WorkspaceExecute.buildWorkspace(req, listener);
    }

    static final double OPEN_LOOP_OVER_ESTIMATE = BuildEta.OPEN_LOOP_OVER_ESTIMATE;

    static long preferSlightOverEstimate(long baseMs) {
        return BuildEta.preferSlightOverEstimate(baseMs);
    }

    static boolean isFullWorkShape(HistoryShape hist, List<EffortWeights.ModuleCost> costs) {
        return BuildEta.isFullWorkShape(hist, costs);
    }

    static int substantialModuleCount(List<EffortWeights.ModuleCost> costs) {
        return BuildEta.substantialModuleCount(costs);
    }

    static void logSeedQuality(long seedMs, long actualExecuteMs, int dirtyModules) {
        BuildEta.logSeedQuality(seedMs, actualExecuteMs, dirtyModules);
    }

    static long applyHistoryPrior(long base, BuildMetrics.Stats okHist) {
        return BuildEta.applyHistoryPrior(base, okHist);
    }

    /** @param rebuildShape ignored — kept for call-site compatibility; step composition owns ETA. */
    static long applyHistoryPrior(long base, BuildMetrics.Stats okHist, boolean rebuildShape) {
        return BuildEta.applyHistoryPrior(base, okHist, rebuildShape);
    }

    /**
     * @param rebuildShape ignored (API compat)
     * @param dirtyModules ignored (API compat)
     */
    static long applyHistoryPrior(long base, BuildMetrics.Stats okHist, boolean rebuildShape, int dirtyModules) {
        return BuildEta.applyHistoryPrior(base, okHist, rebuildShape, dirtyModules);
    }

    static BuildMetrics.Stats okHistory(Path entryDir) {
        return BuildEta.okHistory(entryDir);
    }

    static HistoryShape historyShape() {
        return BuildEta.historyShape();
    }

    /**
     * @param rebuild whether this run is a force/rebuild (full work)
     * @param dirtyModules dirty module count, or {@code -1} when unknown at seed time
     */
    public record HistoryShape(boolean rebuild, int dirtyModules) {
        public String kind() {
            return rebuild ? "build:rebuild" : "build";
        }

        /** Metrics dir key: {@code path} or {@code path#dN} when dirty count known. */
        public String dirKey(@Nullable Path entryDir) {
            if (entryDir == null) return "";
            String base = BuildMetrics.slashKey(entryDir.toString());
            if (dirtyModules >= 0) return base + "#d" + dirtyModules;
            return base;
        }
    }

    static BuildMetrics.Stats okHistory(Path entryDir, HistoryShape shape) {
        return BuildEta.okHistory(entryDir, shape);
    }

    /**
     * True when any productive step (compile / test / package / native / image / …) terminated
     * {@link TaskStatus#SUCCESS} rather than cache-hit {@link TaskStatus#SKIPPED}. Setup steps
     * (parse, resolve, ensure-jdk, copy-resources, write-stamp) always succeed without marking
     * cached and must not make a pure check look like a rebuild.
     */
    public static boolean moduleDidWork(BuildPlanResult r) {
        for (BuildPlanResult.StepReport s : r.steps()) {
            if (s.status() != TaskStatus.SUCCESS) continue;
            if (isProductiveStep(s.name())) return true;
        }
        return false;
    }

    /** Steps whose real work (not a no-op/cache hit) means the module was "built", not just checked. */
    public static boolean isProductiveStep(String name) {
        if (name == null || name.isEmpty()) return false;
        return name.startsWith("compile")
                || name.equals(TaskNames.RUN_TESTS)
                || name.startsWith("package")
                || name.startsWith("native")
                || name.startsWith(TaskNames.WRITE_IMAGE)
                || name.startsWith("image-")
                || name.contains(TaskNames.KSP)
                || name.startsWith("transform");
    }

    /** Apply the subset of {@code workspaceLinks} whose sources live under {@code moduleDir} (best-effort). */
    public static void linkModuleArtifacts(Path moduleDir, Map<Path, Path> workspaceLinks) {
        WorkspaceArtifacts.linkModule(moduleDir, workspaceLinks);
    }
}
