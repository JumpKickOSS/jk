// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.EffortWeights;
import cc.jumpkick.runtime.base.WorkspaceArtifacts;
import cc.jumpkick.wire.runtime.BuildKnobs;
import cc.jumpkick.wire.runtime.EtaSpread;
import cc.jumpkick.wire.runtime.ExplainPlan;
import cc.jumpkick.wire.runtime.ModuleWorkCost;
import cc.jumpkick.wire.runtime.RemainingWork;
import cc.jumpkick.wire.runtime.StepCost;
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
     * Forecast the build without running it, under the request's {@code knobs}, confined to their
     * selection's cone — the modules {@code jk build -m} with the same selection schedules, priced
     * the way its countdown seed prices them. An empty selection is the whole workspace. See {@link
     * BuildForecasting#explain}.
     */
    public static ExplainPlan explain(Path entryDir, JkBuild entryBuild, Path cache, BuildKnobs knobs)
            throws IOException {
        return BuildForecasting.explain(entryDir, entryBuild, cache, knobs);
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
     * maxModuleConcurrency}/{@code parallelTests}, same {@link BuildEta} seed — one plan prices
     * bit-for-bit for a given workspace and session, whichever forecast produced it.
     *
     * <p>The plans agree except where the two forecasts ask different compiler workers for Zinc's
     * plan of a compile ({@link cc.jumpkick.task.JavaCompile.PlanWorkers}), and the rule is who else
     * is on the workers: {@code jk explain} plans on any (its answer is the plan), as does a build's
     * preflight walk, which runs before any compile; the walk a build prices beside its running
     * compiles ({@code BackgroundEta}: a memo hit's scheduled modules, a selection, {@code
     * --rebuild}/{@code --force}) plans on none and predicts each compile that has an analysis from
     * its prior record. Only there can a compile's verdict, and so the seed, differ from explain's.
     * See {@code docs/contributors/progress-contract.md}.
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
            /**
             * Whether the estimate was priced from the step ledger's history. A first build has
             * none: its compile walls are the history there is, and a cold worker's are kept.
             */
            boolean measured,
            /**
             * Per module, the steps it is priced to run with their own prices: the live remainder
             * counts each module down by its steps. Empty for an empty estimate.
             */
            Map<Path, List<StepCost>> steps,
            /**
             * The modules a step-priced model expects to run a step in, the work set its steps were
             * priced under: the step ledger records the finished run under it ({@code RunShapes}).
             */
            Set<String> frontier,
            /**
             * Per module dir, the curve its suite's class share is read as time through ({@code
             * SuiteCurve}): the latest its suite measured in the ledger.
             */
            Map<String, int[]> curves,
            /**
             * Whether the run was priced as a full rebuild (forced, or compiling most modules): with
             * {@link #frontier} the shape a re-pricing keeps, since a compile that has finished by
             * then would read as cached and move both.
             */
            boolean fullRebuild) {
        public EtaModel {
            steps = steps == null ? Map.of() : Map.copyOf(steps);
            frontier = frontier == null ? Set.of() : Set.copyOf(frontier);
            curves = curves == null ? Map.of() : Map.copyOf(curves);
        }

        public EtaModel(long etaMs, List<EffortWeights.ModuleCost> costs, int concurrency, boolean serial) {
            this(etaMs, costs, concurrency, serial, false, Map.of(), Set.of(), Map.of(), false);
        }

        static EtaModel empty() {
            return new EtaModel(0, List.of(), 1, true);
        }

        /**
         * The estimate with its spread (JK-R0N5S): its steps' spreads propagated through its
         * schedule ({@link RemainingWork#residual}), anchored at {@link #etaMs}. An estimate with no
         * steps carries the spread of a module priced by weight alone. {@code correlation} is how
         * strongly the host's step errors go the same way ({@code RungBias#correlation}).
         */
        public RemainingWork.Residual residual(boolean parallelTests, double correlation) {
            if (etaMs <= 0) return new RemainingWork.Residual(0, 0);
            if (steps.isEmpty() || costs.isEmpty()) return new RemainingWork.Residual(etaMs, EtaSpread.UNPRICED);
            List<ModuleWorkCost> work = new ArrayList<>();
            for (EffortWeights.ModuleCost c : costs) work.add(c.toWorkCost());
            double spread = RemainingWork.seedSteps(work, steps, concurrency, serial, parallelTests)
                    .withCorrelation(correlation)
                    .residual()
                    .spread();
            return new RemainingWork.Residual(etaMs, spread);
        }
    }

    public static EtaModel estimateEtaModel(
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
     * Module-concurrency budget for the ETA schedule — <b>must</b> match {@link
     * #buildWorkspace}'s {@code concurrency} so explain and the live countdown clamp the same way.
     */
    static int etaConcurrency(int maxModuleConcurrency) {
        return BuildEta.etaConcurrency(maxModuleConcurrency);
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
     * {@code HeapPlan} state sized for just itself.
     */
    public static WorkspaceResult buildWorkspace(WorkspaceRequest req, WorkspaceBuildListener listener) {
        return WorkspaceExecute.buildWorkspace(req, listener);
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
}
