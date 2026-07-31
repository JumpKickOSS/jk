// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cache.Linking;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.HeapPlan;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.pubgrub.UnsatisfiableException;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.PipelineKey;
import cc.jumpkick.run.PipelineListener;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.StepStatus;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.task.ActionCache;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Engine-side build facade for any front-end. Pure orchestration — nothing writes stdout/stderr;
 * results are returned for the caller's view layer.
 */
public final class BuildService {

    private BuildService() {}

    /**
     * Outcome of the pre-build workspace lock-freshness guard.
     *
     * @param status process exit code — {@code 0} means the lock is fresh (or was re-locked OK)
     * @param error a bare message to surface (no command prefix), or {@code null}
     */
    public record LockGuard(int status, String error) {
        public static final LockGuard OK = new LockGuard(0, null);
    }

    /**
     * Ensure the workspace lock reflects its manifests before a build: if the root {@code jk-lock.toml} is
     * absent, older than the root {@code jk.toml}, or older than any declared member manifest, re-run
     * the {@link LockFlow lock pipeline}. Soft failures (I/O, network) don't block the build — the
     * per-module path surfaces genuine problems when it resolves classpaths.
     */
    public static LockGuard ensureWorkspaceLockFresh(Path root, JkBuild rootBuild, Path cache) {
        Path rootLock = cc.jumpkick.lock.LockPaths.lockFile(root);
        if (!workspaceLockStale(root, rootBuild, rootLock)) return LockGuard.OK;
        try {
            LockFlow.Result r = LockFlow.run(root, cache, List.of(), true, null);
            return r.status() != 0 ? new LockGuard(r.status(), r.error()) : LockGuard.OK;
        } catch (UnsatisfiableException e) {
            return new LockGuard(6, e.getMessage());
        } catch (Exception e) {
            return LockGuard.OK; // soft failure — let the per-module path surface real errors
        }
    }

    /**
     * True when {@code rootLock} is absent or older than the root manifest or any declared member
     * manifest — i.e. the merged workspace lock no longer reflects the manifests it was derived from.
     */
    public static boolean workspaceLockStale(Path root, JkBuild rootBuild, Path rootLock) {
        if (!Files.exists(rootLock)) return true;
        if (AutoLock.isStale(root, rootLock)) return true; // root jk.toml newer than the lock
        if (rootBuild.workspace() != null) {
            for (String module : rootBuild.workspace().modules()) {
                Path moduleDir = root.resolve(module).normalize();
                if (AutoLock.isStale(moduleDir, rootLock)) return true; // a member manifest is newer
            }
        }
        return false;
    }

    // =========================================================================
    // Workspace artifact placement
    // =========================================================================

    /**
     * Compute the {@code src → <wsRoot>/target/<name>} hard-link map for a workspace build: every
     * application module's final artifacts (jar / shadow / native binary+library / OCI tar) are
     * surfaced under the workspace root's {@code target/}. On a filename collision across modules the
     * link name is prefixed with the module's group. Pure — {@link #linkModuleArtifacts} applies it.
     */
    public static Map<Path, Path> computeWorkspaceLinks(Iterable<Path> moduleDirs, Path workspaceRoot) {
        Path wsRoot = workspaceRoot.toAbsolutePath().normalize();
        Map<Path, List<Path>> moduleArtifacts = new LinkedHashMap<>();
        Map<Path, String> moduleGroup = new LinkedHashMap<>();
        for (Path moduleDir : moduleDirs) {
            Path normalDir = moduleDir.toAbsolutePath().normalize();
            if (normalDir.equals(wsRoot)) continue;
            Path buildFile = moduleDir.resolve("jk.toml");
            if (!Files.exists(buildFile)) continue;
            JkBuild build;
            try {
                build = JkBuildParser.parse(buildFile);
            } catch (Exception ignored) {
                continue;
            }
            BuildLayout layout = BuildLayout.of(wsRoot, moduleDir, build);
            if (!layout.hasMain()) continue;
            List<Path> candidates = new ArrayList<>();
            candidates.add(layout.mainJar());
            candidates.add(layout.assemblyJar());
            candidates.add(layout.nativeBinary());
            candidates.add(layout.nativeLibrary());
            candidates.add(layout.ociImageTar());
            moduleArtifacts.put(normalDir, candidates);
            moduleGroup.put(normalDir, build.project().group());
        }
        // Count per filename across all modules to detect collisions.
        Map<String, Long> filenameCounts = new HashMap<>();
        for (List<Path> arts : moduleArtifacts.values()) {
            for (Path art : arts) filenameCounts.merge(art.getFileName().toString(), 1L, Long::sum);
        }
        // Build the final src→linkDest map.
        Path wsTarget = wsRoot.resolve("target");
        Map<Path, Path> links = new LinkedHashMap<>();
        for (var entry : moduleArtifacts.entrySet()) {
            Path normalDir = entry.getKey();
            String group = moduleGroup.get(normalDir);
            for (Path art : entry.getValue()) {
                String filename = art.getFileName().toString();
                String linkName = filenameCounts.getOrDefault(filename, 0L) > 1 ? group + "-" + filename : filename;
                links.put(art, wsTarget.resolve(linkName));
            }
        }
        return links;
    }

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
     * for the local preflight dirty memo (JK-1100). When {@code entryDir} is non-null and inputs are
     * unchanged, returns the memoized dirty set without a full {@link BuildPlanForecast} walk.
     */
    public static Set<Path> forecastDirtyDirs(BuildGraph.Result graph, Path cache, boolean skipTests, Path entryDir) {
        return forecastWithFingerprints(graph, cache, skipTests, entryDir).dirty();
    }

    /** A preflight verdict: the dirty set plus the fingerprints it was computed against. */
    record Preflight(Set<Path> dirty, Map<Path, String> fingerprints) {}

    /**
     * As {@link #forecastDirtyDirs} but also returning the fingerprint snapshot taken BEFORE the
     * forecast walk — the only fingerprints a post-build {@link PreflightMemo#storeDirty} may use
     * (fingerprinting after the build records mid-build edits as clean).
     */
    static Preflight forecastWithFingerprints(BuildGraph.Result graph, Path cache, boolean skipTests, Path entryDir) {
        Set<Path> all = new HashSet<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) all.add(u.dir());
        // --force / --rebuild: every module runs — skip the expensive per-step forecast walk.
        if (SessionContext.current().config().rebuildOr(false)
                || SessionContext.current().config().forceOr(false)) {
            return new Preflight(all, Map.of());
        }
        Map<Path, String> fps;
        if (entryDir != null) {
            var memo = PreflightMemo.tryLoadDirty(entryDir, graph, skipTests);
            if (memo.isPresent()) {
                if (Perf.ENABLED) {
                    System.err.println("[jk-perf] preflight-memo hit dirty="
                            + memo.get().dirty().size());
                }
                return new Preflight(memo.get().dirty(), memo.get().fingerprints());
            }
            fps = PreflightMemo.snapshotFingerprints(graph, skipTests);
        } else {
            fps = Map.of();
        }
        try {
            Cas cas = JkStores.cas(cache);
            ActionCache ac = new ActionCache(cas, cache.resolve("actions"));
            Set<Path> dirty = new HashSet<>();
            for (BuildPlan.Module m : BuildPlanForecast.of(graph, cas, ac, cache, skipTests)) {
                if (m.dirty()) dirty.add(m.dir());
                if (Perf.ENABLED && m.dirty()) {
                    for (BuildPlan.Step p : m.steps()) {
                        if (!p.cached())
                            System.err.println("[jk-perf] dirty " + m.coord() + " " + p.name() + " (" + p.text() + ")");
                    }
                }
            }
            if (entryDir != null) {
                PreflightMemo.storeDirty(entryDir, graph, skipTests, dirty, fps);
            }
            return new Preflight(dirty, fps);
        } catch (RuntimeException e) {
            return new Preflight(all, fps);
        }
    }

    // =========================================================================
    // Explain / plan (the front-end-callable dry-run planner)
    // =========================================================================

    /**
     * Forecast the build without running it: resolve the module graph and run the truthful
     * per-step {@link BuildPlanForecast} over it, returning an {@link ExplainPlan} the caller
     * renders. Pure policy — nothing here writes to {@code stdout}/{@code stderr}. Graph-resolution
     * errors come back in {@link ExplainPlan#errors()} (the caller renders the same failure); an
     * {@link IOException} probing the workspace still propagates, exactly as the direct resolve did.
     */
    public static ExplainPlan explain(Path entryDir, JkBuild entryBuild, Path cache) throws IOException {
        BuildGraph.Result graph = BuildGraph.resolve(entryDir, entryBuild);
        if (graph.hasErrors()) {
            return new ExplainPlan(List.of(), Map.of(), 1, List.copyOf(graph.errors()));
        }
        Cas cas = JkStores.cas(cache);
        ActionCache actionCache = new ActionCache(cas, cache.resolve("actions"));
        List<BuildPlan.Module> modules = BuildPlanForecast.of(graph, cas, actionCache, cache);
        return new ExplainPlan(modules, graph.edges(), graph.maxReadyWidth(), List.of());
    }

    /**
     * Predicted wall-clock for building {@code plan}, in millis ({@code 0} = unknown — the estimate
     * never fails an explain).
     *
     * <p><b>Single ETA routine with {@code jk build}</b>: assembles dirty-module costs (shape memo
     * when warm, else pipeline + cached-step zeroing) then calls {@link #seedEta} — the same schedule
     * + history prior the build countdown seeds from. Weight→ms conversion is per-module: a warm
     * module converts at {@link EffortWeights#MS_PER_WEIGHT}; a cold module at this host's {@link
     * Calibration} (the one sanctioned dry-run exception).
     */
    public static long estimateEtaMillis(
            ExplainPlan plan,
            Path entryDir,
            Path cache,
            int workers,
            Path jdksDir,
            String profile,
            boolean skipTests,
            boolean verbose,
            boolean serial,
            boolean parallelTests) {
        try {
            // All modules of this build graph — the project/workspace set each module's prediction
            // borrows a learned rate from when it has no history of its own (EffortWeights.learned).
            Set<Path> projectModules = new HashSet<>();
            for (BuildPlan.Module m : plan.modules()) projectModules.add(m.dir());
            boolean distrust = SessionContext.current().config().forceOr(false)
                    || SessionContext.current().config().rebuildOr(false);
            List<EffortWeights.ModuleCost> costs = new ArrayList<>();
            int shapeHits = 0;
            // Only dirty modules reserve real work (JK-1176). Fully-cached modules would otherwise
            // inflate the estimate (and history-prior of avg full builds would replace base=0).
            for (BuildPlan.Module m : plan.modules()) {
                if (!distrust && !m.dirty()) continue;
                Path mdir = m.dir();
                Set<Path> prereqs = plan.edges().getOrDefault(mdir, Set.of());
                var fromShape = etaCostFromShape(entryDir, mdir, prereqs, skipTests, distrust);
                if (fromShape.isPresent()) {
                    costs.add(fromShape.get());
                    shapeHits++;
                    continue;
                }
                BuildPipelines.Inputs inputs = BuildPlanForecast.inputsFor(
                        mdir, cache, workers, jdksDir, profile, skipTests, verbose, projectModules);
                Pipeline.Builder builder = BuildPipelines.coreBuilder(inputs, true);
                BuildPipelines.appendDeclaredTails(builder, inputs);
                Pipeline pipeline = builder.build();
                int weight = pipeline.estimatedTotalWeight();
                // Charge nothing for forecast-cached steps (JK-1260), or a "Fully Cached" plan still
                // advertises a full-build ETA.
                Set<String> cachedSteps = new HashSet<>();
                if (m.dirty()) {
                    for (BuildPlan.Step s : m.steps()) {
                        if (s.cached()) cachedSteps.add(s.name());
                    }
                } else {
                    for (cc.jumpkick.run.Step s : pipeline.steps()) cachedSteps.add(s.name());
                }
                costs.add(EffortWeights.costOf(mdir, prereqs, pipeline, cachedSteps));
                // Warm the shape memo for the next explain/build ETA path.
                if (!distrust && entryDir != null) {
                    PreflightMemo.storeShape(entryDir, mdir, skipTests, PreflightMemo.shapeOf(pipeline, weight));
                }
            }
            if (Perf.ENABLED && shapeHits > 0) {
                System.err.println("[jk-perf] estimateEta shape-hits=" + shapeHits + "/"
                        + plan.modules().size() + " dirty-costs=" + costs.size());
            }
            // Nothing dirty → nothing to do (do not inject whole-build history average).
            if (costs.isEmpty()) return 0;
            int concurrency = serial
                    ? 1
                    : HeapPlan.requestedJvms(
                            plan.maxReadyWidth(),
                            workers,
                            parallelTests,
                            Runtime.getRuntime().availableProcessors());
            // Same seed as jk build's countdown (schedule + history prior).
            return seedEta(
                    entryDir,
                    costs,
                    costDirs(costs),
                    concurrency,
                    serial,
                    parallelTests,
                    cache,
                    jdksDir,
                    historyShapeForCosts(costs.size()));
        } catch (RuntimeException e) {
            return 0; // never fail explain over the estimate
        }
    }

    // =========================================================================
    // Build preflight (resolve + dirty forecast, for the fully-cached shortcut)
    // =========================================================================

    /**
     * An opaque, front-end-safe handle to a resolved build graph: enough for a caller to branch on
     * resolution errors / an empty workspace and then forecast dirty modules, without ever naming
     * {@link BuildGraph}/{@link BuildGraph.BuildUnit}. The engine-internal {@link BuildGraph.Result}
     * is reachable only through the package-private {@link #graph()} accessor (feeding {@link
     * #forecastDirtyDirs(ResolvedGraph, Path)}), so the boundary is compiler-enforced.
     *
     * <p>A {@code final class} rather than a {@code record} precisely so {@code graph()} can drop
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
    public static Set<Path> forecastDirtyDirs(ResolvedGraph graph, Path cache, boolean skipTests, Path entryDir) {
        return forecastDirtyDirs(graph.graph(), cache, skipTests, entryDir);
    }

    // =========================================================================
    // Workspace build (the front-end-callable event-emitting entry point)
    // =========================================================================

    private static final PipelineKey<TestSummary> TEST_RESULT = PipelineKey.of("test-result", TestSummary.class);

    /**
     * Build a whole workspace: resolve the module graph, size the worker-JVM memory plan (unless
     * {@link WorkspaceRequest#applyMemoryPlan()} is {@code false} — see its javadoc), assemble each
     * module's pipeline, then schedule them in dependency order (each level concurrent) — running every
     * module's pipeline and surfacing artifacts under the workspace {@code target/}. Progress flows to
     * {@code listener}; the returned {@link WorkspaceResult} is the aggregate outcome. Pure of
     * presentation — the caller renders from the events.
     *
     * <p>This method does not assume it is the only in-flight caller in the process: memory planning
     * is opt-out precisely so a host running several concurrent builds in one JVM (a resident engine)
     * can plan once for its own concurrency instead of letting each call overwrite the shared
     * {@code HeapPlan}/{@code PluginSlots} state sized for just itself.
     */
    public static WorkspaceResult buildWorkspace(WorkspaceRequest req, WorkspaceBuildListener listener) {
        // Re-lock when the workspace lock is stale so unsatisfiable deps fail here instead of
        // a false "all up to date" from per-module forecasts. Soft I/O failures don't block.
        if (req.freshenLock()) {
            listener.onPreflight("lock", 0, 0, "Refreshing workspace lock…");
            LockGuard guard = ensureWorkspaceLockFresh(req.entryDir(), req.entryBuild(), req.cache());
            if (guard.status() != 0) {
                WorkspaceResult r = new WorkspaceResult(
                        false,
                        guard.status(),
                        List.of(),
                        List.of(guard.error() != null ? guard.error() : "dependency resolution failed"));
                listener.onWorkspaceFinish(r);
                return r;
            }
            listener.onPreflight("lock", 1, 1, "Workspace lock ready");
        }
        listener.onPreflight("graph", 0, 0, "Resolving module graph…");
        BuildGraph.Result graph;
        try {
            graph = BuildGraph.resolve(req.entryDir(), req.entryBuild());
        } catch (IOException e) {
            WorkspaceResult r = new WorkspaceResult(false, 2, List.of(), List.of(String.valueOf(e.getMessage())));
            listener.onWorkspaceFinish(r);
            return r;
        }
        if (graph.hasErrors()) {
            WorkspaceResult r = new WorkspaceResult(false, 2, List.of(), List.copyOf(graph.errors()));
            listener.onWorkspaceFinish(r);
            return r;
        }
        List<BuildGraph.BuildUnit> units = graph.topoOrder();
        // JK-1109: compare to prior structure memo before overwriting (fail-open).
        boolean graphMemoHit = PreflightMemo.graphStructureMatches(req.entryDir(), graph);
        PreflightMemo.storeGraph(req.entryDir(), graph);
        if (Perf.ENABLED && graphMemoHit) {
            System.err.println("[jk-perf] preflight-graph-memo structure-match units=" + units.size());
        }
        listener.onPreflight("graph", 1, 1, units.size() + " modules" + (graphMemoHit ? " (memo)" : ""));
        if (units.isEmpty()) {
            WorkspaceResult r = new WorkspaceResult(true, 0, List.of(), List.of());
            listener.onWorkspaceFinish(r);
            return r;
        }
        // Size worker-JVM heaps/concurrency from free memory before any fork (engine resource plan).
        int cap = Runtime.getRuntime().availableProcessors();
        boolean parallelTests = SessionContext.current().parallelTests();
        int width = BuildGraph.maxReadyWidth(units, graph.edges());
        // A module-concurrency cap (e.g. -j1 → 1) bounds the peak module count for both the
        // memory plan and the ETA below, so serial builds size heaps and estimate time as serial.
        if (req.maxModuleConcurrency() > 0) width = Math.min(width, req.maxModuleConcurrency());
        if (req.applyMemoryPlan()) {
            JvmOptions.planAndApply(
                    HeapPlan.requestedJvms(width, req.workers() > 0 ? req.workers() : 1, parallelTests, cap));
        }

        Set<Path> moduleDirs = new LinkedHashSet<>();
        for (BuildGraph.BuildUnit u : units) moduleDirs.add(u.dir());
        long tf = Perf.start();
        // JK-1106: Checking runs inside this build request (no separate client forecast RPC).
        // Client dirty hint (selection / force path) still avoids a second walk when provided.
        // --force/--rebuild short-circuits forecastDirtyDirs to "all" without per-step hashing.
        // JK-1100: when forecasting here, consult/store the local dirty memo under target/.jk/preflight/.
        Set<Path> dirty;
        if (req.dirtyHint() != null) {
            listener.onPreflight("checking", 0, 0, "Using dirty set…");
            dirty = req.dirtyHint();
            listener.onPreflight(
                    "checking", 1, 1, dirty.isEmpty() ? "Nothing dirty" : dirty.size() + " module(s) dirty");
        } else {
            listener.onPreflight("checking", 0, 0, "Checking cache…");
            Preflight preflight = forecastWithFingerprints(graph, req.cache(), req.skipTests(), req.entryDir());
            dirty = preflight.dirty();
            listener.onPreflight(
                    "checking", 1, 1, dirty.isEmpty() ? "All modules up to date" : dirty.size() + " module(s) dirty");
        }
        Perf.end("ws-forecast(hint=" + (req.dirtyHint() != null) + ",dirty=" + dirty.size() + ")", tf);

        // Each module's step durations feed one shared sink, folded into the learned ledger on success.
        List<StepTimings.Sample> timingSamples = Collections.synchronizedList(new ArrayList<>());
        // JK-1102: only fully prepare modules that will execute (dirty). Clean modules skip prepare
        // and schedule — prepare is pure pipeline assembly (parse + plugin describe + step list);
        // real plugin work runs in steps. ensureMaterialized is idempotent CAS extract (JK-1107).
        List<BuildGraph.BuildUnit> dirtyUnits = new ArrayList<>();
        List<BuildGraph.BuildUnit> cleanUnits = new ArrayList<>();
        for (BuildGraph.BuildUnit u : units) {
            if (dirty.contains(u.dir())) dirtyUnits.add(u);
            else cleanUnits.add(u);
        }

        int requestedJvms = HeapPlan.requestedJvms(width, req.workers() > 0 ? req.workers() : 1, parallelTests, cap);
        // Clamp the ETA's module concurrency to the cap so a serial build (cap 1) estimates serially.
        final int concurrency =
                req.maxModuleConcurrency() > 0 ? Math.min(requestedJvms, req.maxModuleConcurrency()) : requestedJvms;

        // JK-1114/1115 / JK-1151: early ETA during prepare — same seedEta routine as jk explain.
        // When every dirty module has a warm shape memo (and not force/rebuild):
        //   • provisional onPlan — bar denominator calibrates during prepare
        //   • early onEtaEstimate from schedule + history (identical to estimateEtaMillis)
        // On force/rebuild (or partial shapes): still seed countdown from history alone so the
        // TUI never counts elapsed-up for the whole prepare window when metrics exist.
        boolean distrustShape = SessionContext.current().config().forceOr(false)
                || SessionContext.current().config().rebuildOr(false);
        boolean serialEta = concurrency <= 1;
        if (!dirtyUnits.isEmpty()) {
            List<EffortWeights.ModuleCost> earlyCosts = new ArrayList<>();
            List<ModulePlan> provisional = new ArrayList<>();
            boolean allShaped = !distrustShape;
            if (allShaped) {
                for (BuildGraph.BuildUnit u : dirtyUnits) {
                    var shaped = etaCostFromShape(
                            req.entryDir(),
                            u.dir(),
                            graph.edges().getOrDefault(u.dir(), Set.of()),
                            req.skipTests(),
                            false);
                    if (shaped.isEmpty()) {
                        allShaped = false;
                        break;
                    }
                    earlyCosts.add(shaped.get());
                    var shape = PreflightMemo.tryLoadShape(req.entryDir(), u.dir(), req.skipTests());
                    provisional.add(PreflightMemo.provisionalModulePlan(u, shape.orElseThrow(), req.cache()));
                }
            }
            if (allShaped) {
                if (Perf.ENABLED) {
                    System.err.println("[jk-perf] early-plan+eta from shape-memo dirty=" + dirtyUnits.size());
                }
                listener.onPlan(List.copyOf(provisional));
                listener.onModuleGraph(graph.edges());
                // Identical call shape to estimateEtaMillis → countdown matches `jk explain`.
                listener.onEtaEstimate(seedEta(
                        req.entryDir(),
                        earlyCosts,
                        costDirs(earlyCosts),
                        concurrency,
                        serialEta,
                        parallelTests,
                        req.cache(),
                        req.jdksDir(),
                        historyShapeForCosts(earlyCosts.size())));
            } else {
                // History-only early seed (rebuild/force or cold shapes) — JK-1151 / JK-1179.
                // Prefer shape-aware rebuild history when this session is rebuild/force so the TUI
                // can countdown before prepare finishes (never stay at 0 when journal has priors).
                // Use dirty-count shape so the post-prepare reseed (same key) can only refine, not
                // jump to a different history tier.
                HistoryShape earlyShape = historyShapeForCosts(dirtyUnits.size());
                long early = applyHistoryPrior(0, okHistory(req.entryDir(), earlyShape), earlyShape.rebuild());
                if (early <= 0 && distrustShape && !dirtyUnits.isEmpty()) {
                    // Cold machine: seed a coarse countdown from dirty-module count so rebuild does
                    // not start in pure count-up mode (JK-1179). ~1.2s per module @ MS_PER_WEIGHT.
                    early = (long) dirtyUnits.size() * EffortWeights.MS_PER_WEIGHT * 8L;
                }
                if (early > 0) listener.onEtaEstimate(early);
            }
        }

        long tp = Perf.start();
        int nPrepare = dirtyUnits.size();
        listener.onPreflight(
                "plan", 0, Math.max(nPrepare, 1), nPrepare == 0 ? "Nothing to prepare" : "Preparing modules…");
        Map<Path, ModulePlan> plans;
        try {
            plans = prepareModules(dirtyUnits, req, moduleDirs, listener, nPrepare, timingSamples);
        } catch (PrepareFailed e) {
            ModuleOutcome o = new ModuleOutcome(e.coord(), e.dir(), false, 2, 0);
            listener.onModuleFinish(o);
            WorkspaceResult r = new WorkspaceResult(false, 2, List.of(o), List.of());
            listener.onWorkspaceFinish(r);
            return r;
        }
        if (nPrepare == 0) {
            listener.onPreflight("plan", 1, 1, "Nothing to prepare");
        }
        Perf.end("ws-prepare-modules(dirty=" + nPrepare + ",clean=" + cleanUnits.size() + ")", tp);
        // Execute plan is dirty modules only — bar/ETA reserve real work, not clean skips.
        listener.onPlan(List.copyOf(plans.values()));
        listener.onModuleGraph(graph.edges());

        // ETA seed — same cost sources + seedEta as estimateEtaMillis (jk explain). Do not mix
        // ModulePlan.weight() with a separately estimated test slice: weight/testWeight must come
        // from one source (shape pair, or pipeline walk) or the countdown diverges from explain.
        long teta = Perf.start();
        List<EffortWeights.ModuleCost> etaCosts = new ArrayList<>();
        for (var e : plans.entrySet()) {
            ModulePlan p = e.getValue();
            Set<Path> prereqs = graph.edges().getOrDefault(e.getKey(), Set.of());
            etaCosts.add(etaCostForPreparedModule(req, p, prereqs, distrustShape));
        }
        Perf.end("ws-eta-costs", teta);
        listener.onEtaEstimate(seedEta(
                req.entryDir(),
                etaCosts,
                costDirs(etaCosts),
                concurrency,
                serialEta,
                parallelTests,
                req.cache(),
                req.jdksDir(),
                historyShapeForCosts(etaCosts.size())));

        // Workspace artifact links for the whole graph (clean modules still own jars from prior builds).
        Map<Path, Path> wsLinks = computeWorkspaceLinks(moduleDirs, req.entryDir());
        for (BuildGraph.BuildUnit u : cleanUnits) {
            linkModuleArtifacts(u.dir(), wsLinks);
        }

        List<ModuleOutcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        List<Double> observedRates = Collections.synchronizedList(new ArrayList<>());
        long tsched = Perf.start();
        ModuleOutcome failure = null;
        if (!dirtyUnits.isEmpty()) {
            failure = WorkspaceScheduler.run(
                    dirtyUnits,
                    BuildGraph.BuildUnit::dir,
                    graph.edges(),
                    u -> runModule(plans.get(u.dir()), listener),
                    (ready, results, _) -> {
                        for (int i = 0; i < results.size(); i++) {
                            ModuleOutcome o = results.get(i);
                            outcomes.add(o);
                            if (!o.success()) return o; // fail-fast
                            linkModuleArtifacts(ready.get(i).dir(), wsLinks);
                            ModulePlan p = plans.get(ready.get(i).dir());
                            // Skip fully-cached modules — their near-zero time isn't representative of work.
                            if (p != null && !p.fullyCached() && p.weight() > 0 && o.millis() > 0)
                                observedRates.add(o.millis() / (double) p.weight());
                        }
                        // No mid-execute onEtaEstimate: TUI clock is pure wall-clock from the seed
                        // (jk explain figure). Live re-projections jumped countdown / reset count-up.
                        // Throughput still folds into Calibration + StepTimings on success below.
                        return null;
                    },
                    req.maxModuleConcurrency());
        }
        Perf.end("ws-schedule-run", tsched);
        // Session cancel (Ctrl-C / jk cancel / web) may finish modules with a non-success exit
        // without a distinct flag — fold SessionCancel into the aggregate so clients settle as
        // cancelled rather than a generic failure (JK-1252).
        boolean cancelled = cc.jumpkick.run.SessionCancel.cancelled();
        boolean ok = failure == null && !cancelled;
        if (ok) {
            // Fold this run's step durations + measured throughput into the learned ledger + host
            // calibration (EWMA) so the next build's estimate is time-accurate. Failed builds don't
            // record — their step times are abnormal.
            StepTimings.record(req.cache(), timingSamples, StepTimings.DEFAULT_ALPHA, System.currentTimeMillis());
            Double runMpw = medianRate(observedRates);
            if (runMpw != null) Calibration.refine(runMpw, System.currentTimeMillis());
            // JK-1100 / JK-1296: after a successful full forecast path, store an all-clean dirty
            // memo so the next process skips the action-key walk. Dirty-hint paths (selection)
            // leave the memo alone — we didn't recompute the whole graph's dirtiness. Test-only
            // runs also leave it alone: they never package, so "clean" would be a lie for build.
            // Re-snapshot fingerprints *now* (not the preflight snapshot): auto-lock / engine-pin
            // rewrites during the run would otherwise poison the next preflight (memo miss →
            // re-enter every module). Mid-build source edits during a monorepo build are not a
            // supported workflow; the next intentional edit still busts the memo on the following run.
            if (req.dirtyHint() == null && !req.testOnly()) {
                Map<Path, String> fps = PreflightMemo.snapshotFingerprints(graph, req.skipTests());
                if (!fps.isEmpty()) {
                    PreflightMemo.storeDirty(req.entryDir(), graph, req.skipTests(), Set.of(), fps);
                }
            }
        }
        int exit = ok ? 0 : (cancelled ? 1 : failure.exitCode());
        WorkspaceResult result =
                new WorkspaceResult(ok, exit, List.copyOf(outcomes), List.of(), cancelled);
        listener.onWorkspaceFinish(result);
        return result;
    }

    /**
     * Prepare pipelines for dirty modules only (JK-1102). When more than one module needs prepare and
     * {@code JK_PREPARE_PARALLEL} is not {@code false}, prepares in parallel on {@link JkThreads#io()}
     * (JK-1103). Dirty modules always {@code forceRebuild} the pipeline assembly path.
     */
    private static Map<Path, ModulePlan> prepareModules(
            List<BuildGraph.BuildUnit> dirtyUnits,
            WorkspaceRequest req,
            Set<Path> moduleDirs,
            WorkspaceBuildListener listener,
            int nPrepare,
            List<StepTimings.Sample> timingSamples) {
        if (dirtyUnits.isEmpty()) return Map.of();
        boolean parallel = nPrepare > 1 && prepareParallelEnabled();
        if (!parallel) {
            Map<Path, ModulePlan> plans = new LinkedHashMap<>();
            int prepared = 0;
            for (BuildGraph.BuildUnit u : dirtyUnits) {
                ModulePlan p = prepareModule(u, req, moduleDirs, true);
                prepared++;
                listener.onPreflight(
                        "plan", prepared, nPrepare, "Preparing " + u.coord() + " (" + prepared + "/" + nPrepare + ")");
                if (p == null) throw new PrepareFailed(u.coord(), u.dir());
                p.pipeline().addListener(timingsRecorder(p, timingSamples));
                plans.put(u.dir(), p);
            }
            return plans;
        }
        AtomicInteger prepared = new AtomicInteger();
        Object preflightLock = new Object();
        Map<Path, ModulePlan> plans = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>(dirtyUnits.size());
        for (BuildGraph.BuildUnit u : dirtyUnits) {
            futures.add(CompletableFuture.runAsync(
                    () -> {
                        ModulePlan p = prepareModule(u, req, moduleDirs, true);
                        if (p == null) throw new PrepareFailed(u.coord(), u.dir());
                        p.pipeline().addListener(timingsRecorder(p, timingSamples));
                        plans.put(u.dir(), p);
                        int n = prepared.incrementAndGet();
                        synchronized (preflightLock) {
                            listener.onPreflight(
                                    "plan", n, nPrepare, "Preparing " + u.coord() + " (" + n + "/" + nPrepare + ")");
                        }
                    },
                    JkThreads.io()));
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            if (c instanceof PrepareFailed pf) throw pf;
            if (c instanceof RuntimeException re) throw re;
            throw new RuntimeException(c);
        }
        // Preserve topo order of dirty units in the plan map for stable onPlan order.
        Map<Path, ModulePlan> ordered = new LinkedHashMap<>();
        for (BuildGraph.BuildUnit u : dirtyUnits) ordered.put(u.dir(), plans.get(u.dir()));
        return ordered;
    }

    /** Parallel prepare is on by default; set {@code JK_PREPARE_PARALLEL=false} to force serial. */
    private static boolean prepareParallelEnabled() {
        String v = System.getenv("JK_PREPARE_PARALLEL");
        return v == null || !v.equalsIgnoreCase("false");
    }

    /** Failed {@link #prepareModule} for a dirty unit — surfaces as exit 2 to the workspace caller. */
    private static final class PrepareFailed extends RuntimeException {
        private final String coord;
        private final Path dir;

        PrepareFailed(String coord, Path dir) {
            super("prepare failed: " + coord);
            this.coord = coord;
            this.dir = dir;
        }

        String coord() {
            return coord;
        }

        Path dir() {
            return dir;
        }
    }

    /**
     * Single schedule-aware ETA (ms) used by both {@code jk explain} and {@code jk build}'s initial
     * countdown. Each module converts weight→ms at its own rate — warm (learned timings) at {@link
     * EffortWeights#MS_PER_WEIGHT}; cold at host {@link Calibration} or static {@link
     * EffortWeights#MS_PER_WEIGHT}. History prior fills base=0 or clamps absurd over-estimates.
     * Pure count-up only when costs are empty and no history exists.
     */
    private static long seedEta(
            Path entryDir,
            List<EffortWeights.ModuleCost> costs,
            Set<Path> dirs,
            int concurrency,
            boolean serial,
            boolean parallelTests,
            Path cache,
            Path jdksDir,
            HistoryShape shape) {
        HistoryShape hist = shape == null ? historyShape() : shape;
        if (costs == null || costs.isEmpty()) {
            return applyHistoryPrior(0, okHistory(entryDir, hist), hist.rebuild());
        }
        StepTimings timings = StepTimings.load(cache);
        java.util.function.Predicate<Path> warm = dir -> timings.hasTimingsFor(List.of(dir.toString()));
        Set<Path> costDirs = dirs != null && !dirs.isEmpty() ? dirs : costDirs(costs);
        boolean anyCold = costDirs.stream().anyMatch(dir -> !warm.test(dir));
        Calibration cal = anyCold ? Calibration.ensure(jdksDir) : null;
        double coldRate = cal != null && cal.present() ? cal.msPerWeight() : EffortWeights.MS_PER_WEIGHT;
        // Always schedule — never drop to base=0 solely because a module is StepTimings-cold.
        long base = EffortWeights.scheduleMillis(
                costs,
                concurrency,
                serial,
                parallelTests,
                dir -> warm.test(dir) ? EffortWeights.MS_PER_WEIGHT : coldRate);
        return applyHistoryPrior(base, okHistory(entryDir, hist), hist.rebuild());
    }

    /** History key with known dirty-module count so explain and build share the same prior tier. */
    private static HistoryShape historyShapeForCosts(int dirtyModuleCount) {
        boolean rebuild = SessionContext.current().config().rebuildOr(false)
                || SessionContext.current().config().forceOr(false);
        return new HistoryShape(rebuild, Math.max(0, dirtyModuleCount));
    }

    private static Set<Path> costDirs(List<EffortWeights.ModuleCost> costs) {
        Set<Path> dirs = new LinkedHashSet<>();
        if (costs != null) {
            for (EffortWeights.ModuleCost c : costs) dirs.add(c.dir());
        }
        return dirs;
    }

    /**
     * Shape-memo cost when warm: both {@code weight} and {@code testWeight} from the same row so the
     * schedule's critical-path / serial-test bounds stay consistent (splitting sources was the
     * explain-vs-countdown divergence).
     */
    private static java.util.Optional<EffortWeights.ModuleCost> etaCostFromShape(
            Path entryDir, Path moduleDir, Set<Path> prereqs, boolean skipTests, boolean distrust) {
        if (distrust || entryDir == null || moduleDir == null) return java.util.Optional.empty();
        return PreflightMemo.tryLoadShape(entryDir, moduleDir, skipTests)
                .map(s -> EffortWeights.costOf(moduleDir, prereqs, s.weight(), s.testWeight()));
    }

    /**
     * Cost for a prepared dirty module: prefer shape pair (matches explain), else walk the prepared
     * pipeline so weight and testWeight stay coupled.
     */
    private static EffortWeights.ModuleCost etaCostForPreparedModule(
            WorkspaceRequest req, ModulePlan p, Set<Path> prereqs, boolean distrustShape) {
        var shaped = etaCostFromShape(req.entryDir(), p.dir(), prereqs, req.skipTests(), distrustShape);
        if (shaped.isPresent()) return shaped.get();
        return EffortWeights.costOf(p.dir(), prereqs, p.pipeline());
    }

    /**
     * The whole-build history sanity anchor: never "count up" when this project <em>or host</em>
     * has real finished builds to average (JK-1151), and never a seed wildly beyond anything this
     * project has ever done (an over-predicted cold estimate is clamped to 2× the historical max).
     * One-sided on purpose: {@code base} prices only <em>this run's</em> mostly-cached, incremental
     * work, which legitimately beats the historical average — clamping up would wreck every
     * incremental estimate. Success-only stats: failed/cancelled runs have abnormal durations,
     * matching what {@link StepTimings}/{@link Calibration} learn from.
     *
     * <p>JK-1178: for rebuild-shaped history, when the schedule base still looks cold (≫ trained
     * avg), blend toward history so {@code explain --rebuild} tracks measured rebuild wall.
     */
    static long applyHistoryPrior(long base, BuildMetrics.Stats okHist) {
        return applyHistoryPrior(base, okHist, false);
    }

    static long applyHistoryPrior(long base, BuildMetrics.Stats okHist, boolean rebuildShape) {
        if (okHist == null || okHist.count() == 0) return base;
        if (base == 0) return okHist.avgMillis();
        long histAvg = okHist.avgMillis();
        if (rebuildShape && histAvg > 0 && base > histAvg * 3 / 2) {
            // Schedule overshot trained rebuilds — pull toward history (α≈0.3 schedule / 0.7 hist).
            return Math.round(0.3 * base + 0.7 * histAvg);
        }
        if (okHist.count() >= 3 && base > 2 * okHist.maxMillis()) return 2 * okHist.maxMillis();
        return base;
    }

    /**
     * Successful build invocation stats with JK-1156 shape-aware keys.
     *
     * <p>Lookup order: exact shaped key → bare project dir → host {@code dir=""} for that shape's
     * kind → host bare {@code build}. Kind is {@code build} or {@code build:rebuild} so full
     * rebuild averages do not pollute incremental ETAs (and vice versa).
     */
    static BuildMetrics.Stats okHistory(Path entryDir) {
        return okHistory(entryDir, historyShape());
    }

    /** Resolve history shape from the ambient session (rebuild/force + dirty-count hint). */
    static HistoryShape historyShape() {
        var cfg = SessionContext.current().config();
        boolean rebuild = cfg.rebuildOr(false) || cfg.forceOr(false);
        return new HistoryShape(rebuild, -1);
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
        public String dirKey(Path entryDir) {
            if (entryDir == null) return "";
            String base = entryDir.toString();
            if (dirtyModules >= 0) return base + "#d" + dirtyModules;
            return base;
        }
    }

    static BuildMetrics.Stats okHistory(Path entryDir, HistoryShape shape) {
        BuildMetrics metrics = BuildMetrics.load(BuildMetrics.defaultFile());
        HistoryShape s = shape == null ? new HistoryShape(false, -1) : shape;
        String kind = s.kind();
        if (entryDir != null) {
            String shaped = s.dirKey(entryDir);
            var exact = metrics.invocation(kind, shaped).map(BuildMetrics.Entry::ok);
            if (exact.isPresent() && exact.get().count() > 0) return exact.get();
            // Same path, any dirty-count for this kind — the write side always shapes the
            // key (path#dN), so merge across shapes instead of an exact bare lookup that
            // reads a never-written key (JK-1226).
            BuildMetrics.Stats shapes = metrics.okAcrossShapes(kind, entryDir.toString());
            if (shapes.count() > 0) return shapes;
            // Fall back to plain "build" for the path (pre-1156 rows).
            if (!"build".equals(kind)) {
                var legacy = metrics.invocation("build", entryDir.toString()).map(BuildMetrics.Entry::ok);
                if (legacy.isPresent() && legacy.get().count() > 0) return legacy.get();
            }
        }
        var hostShaped = metrics.invocation(kind, "").map(BuildMetrics.Entry::ok);
        if (hostShaped.isPresent() && hostShaped.get().count() > 0) return hostShaped.get();
        return metrics.invocation("build", "").map(BuildMetrics.Entry::ok).orElse(BuildMetrics.Stats.EMPTY);
    }

    /** Median of observed per-module ms/weight rates, or null when none recorded yet. */
    private static Double medianRate(List<Double> rates) {
        List<Double> sorted;
        synchronized (rates) {
            if (rates.isEmpty()) return null;
            sorted = new ArrayList<>(rates);
        }
        sorted.sort(Double::compareTo);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    /**
     * Assemble one dirty module's pipeline + estimates. Only called for modules in the dirty set
     * (JK-1102); clean modules never enter here. {@code forceRebuild} seeds {@link
     * EffortWeights#predict} so bar weights don't collapse as fully-cached for upstream-dirty work.
     * Dirty modules are never fully-cached for plan purposes; {@link BuildPipelines#coreBuilder}
     * still predicts weights once via its lazy plan supplier.
     *
     * <p>JK-1113: when the static pipeline shape fingerprint is warm and the session is not
     * force/rebuild, reuse memoized {@code estimatedTotalWeight} (skip the parallel step estimate).
     */
    private static ModulePlan prepareModule(
            BuildGraph.BuildUnit u, WorkspaceRequest req, Set<Path> moduleDirs, boolean forceRebuild) {
        Path dir = u.dir();
        Path buildFile = dir.resolve("jk.toml");
        if (!Files.exists(buildFile)) return null;
        BuildPipelines.Inputs inputs = BuildPlanForecast.inputsFor(
                        dir,
                        req.cache(),
                        req.workers() > 0 ? req.workers() : 1,
                        req.jdksDir(),
                        req.profile(),
                        req.skipTests(),
                        req.verbose(),
                        moduleDirs,
                        req.testOnly())
                .withVariant(req.variant(), req.clientEnv());
        Pipeline.Builder b = BuildPipelines.coreBuilder(inputs, forceRebuild);
        BuildPipelines.appendDeclaredTails(b, inputs);
        Pipeline pipeline = b.build();
        boolean distrust = SessionContext.current().config().forceOr(false)
                || SessionContext.current().config().rebuildOr(false);
        int weight;
        if (!distrust) {
            var shapeHit = PreflightMemo.tryLoadShape(req.entryDir(), dir, req.skipTests());
            if (shapeHit.isPresent()) {
                weight = shapeHit.get().weight();
                if (Perf.ENABLED) {
                    System.err.println("[jk-perf] shape-memo hit " + u.coord() + " weight=" + weight);
                }
            } else {
                weight = pipeline.estimatedTotalWeight();
                PreflightMemo.storeShape(req.entryDir(), dir, req.skipTests(), PreflightMemo.shapeOf(pipeline, weight));
            }
        } else {
            // Force/rebuild: never trust shape memo fullyCached/weights.
            weight = pipeline.estimatedTotalWeight();
        }
        // Dirty ⇒ not fullyCached for calibration / skip-rate sampling.
        return new ModulePlan(u.dir(), u.coord(), pipeline, weight, false, req.cache());
    }

    /** JK-1155: learn run-tests rates from actual TestSummary counts when present. */
    private static StepTimingsRecorder timingsRecorder(ModulePlan p, List<StepTimings.Sample> timingSamples) {
        return new StepTimingsRecorder(
                p.dir().toString(),
                timingSamples,
                () -> p.pipeline().get(BuildPipelines.TEST_RESULT).orElse(null));
    }

    /** Run one module's pipeline, attaching the caller's per-module listener; map the result to an outcome. */
    private static ModuleOutcome runModule(ModulePlan plan, WorkspaceBuildListener listener) {
        PipelineListener ml = listener.onModuleStart(plan);
        if (ml != null) plan.pipeline().addListener(ml);
        long t0 = System.nanoTime();
        try {
            PipelineResult r = plan.pipeline().run();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            int exit = r.success() ? 0 : exitCodeFor(plan.pipeline());
            // Failures always count as work; successes count only when a productive step ran
            // (not pure cache hits / no-ops — JK-1296).
            boolean didWork = !r.success() || moduleDidWork(r);
            ModuleOutcome o = new ModuleOutcome(plan.coord(), plan.dir(), r.success(), exit, ms, didWork);
            listener.onModuleFinish(o);
            return o;
        } catch (RuntimeException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            ModuleOutcome o = new ModuleOutcome(plan.coord(), plan.dir(), false, 1, ms, true);
            listener.onModuleFinish(o);
            return o;
        }
    }

    /**
     * True when any productive step (compile / test / package / native / image / …) terminated
     * {@link StepStatus#SUCCESS} rather than cache-hit {@link StepStatus#SKIPPED}. Setup steps
     * (parse, resolve, ensure-jdk, copy-resources, write-stamp) always succeed without marking
     * cached and must not make a pure check look like a rebuild (JK-1296).
     */
    public static boolean moduleDidWork(PipelineResult r) {
        for (PipelineResult.StepReport s : r.steps()) {
            if (s.status() != StepStatus.SUCCESS) continue;
            if (isProductiveStep(s.name())) return true;
        }
        return false;
    }

    /** Steps whose real work (not a no-op/cache hit) means the module was "built", not just checked. */
    public static boolean isProductiveStep(String name) {
        if (name == null || name.isEmpty()) return false;
        return name.startsWith("compile")
                || name.equals(cc.jumpkick.run.StepNames.RUN_TESTS)
                || name.startsWith("package")
                || name.startsWith("native")
                || name.startsWith("write-image")
                || name.startsWith("image-")
                || name.contains("ksp")
                || name.startsWith("transform");
    }

    /** Test failures exit 4; every other pipeline failure exits 1. */
    private static int exitCodeFor(Pipeline pipeline) {
        TestSummary tr = pipeline.get(TEST_RESULT).orElse(null);
        return tr != null && !tr.allPassed() ? 4 : 1;
    }

    /** Apply the subset of {@code workspaceLinks} whose sources live under {@code moduleDir} (best-effort). */
    public static void linkModuleArtifacts(Path moduleDir, Map<Path, Path> workspaceLinks) {
        if (workspaceLinks.isEmpty()) return;
        Path normalDir = moduleDir.toAbsolutePath().normalize();
        for (var entry : workspaceLinks.entrySet()) {
            Path src = entry.getKey();
            if (!src.startsWith(normalDir)) continue;
            if (!Files.isRegularFile(src)) continue;
            try {
                Linking.linkOrCopy(src, entry.getValue());
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }
}
