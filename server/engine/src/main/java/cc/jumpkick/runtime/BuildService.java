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
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.run.TaskStatus;
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
     * the {@link LockFlow lock plan}. Soft failures (I/O, network) don't block the build — the
     * per-module path surfaces genuine problems when it resolves classpaths.
     */
    public static LockGuard ensureWorkspaceLockFresh(Path root, JkBuild rootBuild, Path cache) {
        Path rootLock = cc.jumpkick.lock.LockPaths.lockFile(root);
        return ensureWorkspaceLockFresh(root, cache, workspaceLockStale(root, rootBuild, rootLock));
    }

    /**
     * As {@link #ensureWorkspaceLockFresh(Path, JkBuild, Path)} with the staleness answer already
     * computed — callers that just priced the re-lock for the ETA pass it in instead of
     * re-hashing every manifest (JK-1359).
     */
    public static LockGuard ensureWorkspaceLockFresh(Path root, Path cache, boolean stale) {
        if (!stale) return LockGuard.OK;
        long t0 = System.nanoTime();
        try {
            // noDefaultFeatures=false: every freshen resolves with the same feature selection as
            // explicit `jk lock`, so lock content never depends on which path freshened (JK-1358).
            LockFlow.Result r = LockFlow.run(root, cache, List.of(), false, null, /* conservative */ true);
            if (r.status() == 0) {
                recordLockSuccess(root, (System.nanoTime() - t0) / 1_000_000L);
            }
            return r.status() != 0 ? new LockGuard(r.status(), r.error()) : LockGuard.OK;
        } catch (UnsatisfiableException e) {
            return new LockGuard(6, e.getMessage());
        } catch (Exception e) {
            return LockGuard.OK; // soft failure — let the per-module path surface real errors
        }
    }

    /**
     * Remaining-work estimate for a workspace re-lock (ms). Composes host atomized rates from
     * {@link cc.jumpkick.cache.LockTimings} (graph/materialize per package + fixed overhead) scaled by
     * this project's known package count or declared roots. Project-specific whole-lock history is a
     * soft clamp only when the composed figure is absurdly low vs a stable prior of similar size.
     * Never 0 when a re-lock is needed (avoids pure count-up).
     */
    static long estimateLockMillis(Path entryDir, Path cache) {
        int packages = 0;
        int declared = 0;
        try {
            Path lockFile = cc.jumpkick.lock.LockPaths.lockFile(entryDir);
            if (Files.isRegularFile(lockFile)) {
                packages = cc.jumpkick.lock.LockfileReader.read(lockFile)
                        .artifacts()
                        .size();
            }
        } catch (Exception ignored) {
            // unknown package count
        }
        try {
            if (entryDir != null) {
                Path toml = entryDir.resolve("jk.toml");
                if (Files.isRegularFile(toml)) {
                    JkBuild b = JkBuildParser.parseLocal(toml);
                    // Workspace root: merge is done at lock time; package count from the existing
                    // root lock (above) is the best size signal. Declared roots = rough cold seed.
                    for (var scope : cc.jumpkick.model.Scope.values()) {
                        if (scope == cc.jumpkick.model.Scope.PLATFORM) continue;
                        declared += b.dependencies().of(scope).size();
                    }
                }
            }
        } catch (Exception ignored) {
            // unknown declared count
        }
        long composed = cc.jumpkick.cache.LockTimings.estimateMillis(declared, packages);
        // Soft floor from this project's prior whole-lock walls (same dir) — only when composition
        // under-shoots a stable measured average by a wide margin (never pull a large monorepo down).
        try {
            String dir = entryDir == null
                    ? ""
                    : entryDir.toAbsolutePath().normalize().toString();
            BuildMetrics.Stats hist = BuildMetrics.load(BuildMetrics.defaultFile())
                    .invocation("lock", dir)
                    .map(BuildMetrics.Entry::ok)
                    .orElse(BuildMetrics.Stats.EMPTY);
            if (hist.count() >= 2 && hist.avgMillis() > composed * 2) {
                // Prefer composition for size-aware ETA; only lift when history says we routinely
                // take much longer (e.g. cold-ish CAS on this host for this graph).
                composed = Math.round(0.35 * hist.avgMillis() + 0.65 * composed);
            }
        } catch (RuntimeException ignored) {
            // ignore
        }
        return Math.max(200, composed);
    }

    /** Fold a successful lock wall into BuildMetrics under kind {@code lock} (project tier). */
    private static void recordLockSuccess(Path entryDir, long millis) {
        if (millis <= 0 || entryDir == null) return;
        try {
            String dir = entryDir.toAbsolutePath().normalize().toString();
            BuildMetrics.record(
                    BuildMetrics.defaultFile(),
                    new BuildMetrics.Outcome("lock", dir, null, true, false, millis, List.of()),
                    System.currentTimeMillis());
        } catch (Exception ignored) {
            // never fail a build over metrics I/O
        }
    }

    /**
     * True when {@code rootLock} is absent or older than the root manifest or any declared member
     * manifest — i.e. the merged workspace lock no longer reflects the manifests it was derived from.
     */
    public static boolean workspaceLockStale(Path root, JkBuild rootBuild, Path rootLock) {
        // rootBuild is unused for the check — member list comes from the live root manifest inside
        // LockFreshness (digest-aware, clone-safe). Kept on the signature for call-site compat.
        return cc.jumpkick.lock.LockFreshness.workspaceLockStale(root, rootLock);
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

    // =========================================================================
    // Explain / plan (the front-end-callable dry-run planner)
    // =========================================================================

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
     * Predicted wall-clock for building {@code plan}, in millis ({@code 0} = nothing to do / fully
     * cached).
     *
     * <p><b>Hard invariant:</b> this is the <em>only</em> ETA seed used by both {@code jk explain}
     * and {@code jk build}'s countdown. Same forecast plan, same {@code workers}/{@code
     * maxModuleConcurrency}/{@code parallelTests}, same {@link #seedEta} — the numbers must match
     * bit-for-bit for a given workspace and session. See {@code docs/perf/progress-contract.md}.
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
            Path jdksDir,
            String profile,
            boolean skipTests,
            boolean verbose,
            boolean parallelTests,
            int maxModuleConcurrency) {
        return estimateEtaModel(
                        plan,
                        entryDir,
                        cache,
                        workers,
                        jdksDir,
                        profile,
                        skipTests,
                        verbose,
                        parallelTests,
                        maxModuleConcurrency)
                .etaMs();
    }

    /**
     * ETA seed plus the cost assembly it was computed from — the single assembly both the estimate
     * and the {@link WorkModel} consume (JK-1817: the model wiring used to re-run
     * {@code etaCostsFromExplainPlan} unguarded, so an exception the estimate swallowed could fail
     * the whole build over an estimate, and {@code --force} paid the per-module walk twice).
     */
    public record EtaModel(long etaMs, List<EffortWeights.ModuleCost> costs, int concurrency, boolean serial) {
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
        try {
            // Host calibration: cheap when present; bootstrap probe once when missing (network
            // unless --offline). Host scale then multiplies product baselines for cold steps.
            Calibration.ensure(jdksDir);
            List<EffortWeights.ModuleCost> costs =
                    etaCostsFromExplainPlan(plan, cache, workers, jdksDir, profile, skipTests, verbose);
            int concurrency = etaConcurrency(plan.maxReadyWidth(), workers, parallelTests, maxModuleConcurrency);
            boolean serialEta = concurrency <= 1;
            if (costs.isEmpty()) return new EtaModel(0, costs, concurrency, serialEta);
            long etaMs = seedEta(
                    entryDir,
                    costs,
                    costDirs(costs),
                    concurrency,
                    serialEta,
                    parallelTests,
                    cache,
                    jdksDir,
                    historyShapeForCosts(costs.size()));
            return new EtaModel(etaMs, costs, concurrency, serialEta);
        } catch (RuntimeException e) {
            // Never fail explain/build over the estimate — but do not silently advertise 0s/empty.
            System.err.println("jk: ETA estimate failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return EtaModel.empty();
        }
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
            Path jdksDir,
            String profile,
            boolean skipTests,
            boolean verbose,
            boolean serial,
            boolean parallelTests) {
        return estimateEtaMillis(
                plan, entryDir, cache, workers, jdksDir, profile, skipTests, verbose, parallelTests, serial ? 1 : 0);
    }

    /**
     * Module-concurrency budget for the ETA schedule — <b>must</b> match {@link
     * #buildWorkspace}'s {@code concurrency} so explain and the live countdown clamp the same way.
     */
    static int etaConcurrency(int maxReadyWidth, int workers, boolean parallelTests, int maxModuleConcurrency) {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int width = Math.max(1, maxReadyWidth);
        if (maxModuleConcurrency > 0) width = Math.min(width, maxModuleConcurrency);
        // HeapPlan multiplies module width by within-module workers when parallelTests; auto (-w 0)
        // uses 1 here for the peak-JVM product (test auto-parallel is priced inside TestWorkers).
        int wForHeap = workers > 0 ? workers : 1;
        int requested = HeapPlan.requestedJvms(width, wForHeap, parallelTests, cores);
        if (maxModuleConcurrency > 0) return Math.min(requested, maxModuleConcurrency);
        return requested;
    }

    /**
     * Dirty-module costs from a {@link TaskForecaster} plan — sole cost assembly for explain and
     * build countdown. Never prices prepared {@link BuildPlan} weights or shape-memo rows for ETA.
     */
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

    /**
     * Order module costs in the same sequence as {@code units} (workspace topo / dirty list) so
     * first-ready schedule admission matches {@link WorkspaceScheduler}.
     */
    static List<ModuleWorkCost> orderCostsLikeUnits(
            List<BuildGraph.BuildUnit> units, List<EffortWeights.ModuleCost> costs) {
        Map<Path, EffortWeights.ModuleCost> byDir = new LinkedHashMap<>();
        if (costs != null) {
            for (EffortWeights.ModuleCost c : costs) {
                if (c != null && c.dir() != null) byDir.put(c.dir(), c);
            }
        }
        List<ModuleWorkCost> ordered = new ArrayList<>();
        if (units != null) {
            for (BuildGraph.BuildUnit u : units) {
                EffortWeights.ModuleCost c = byDir.remove(u.dir());
                if (c != null) {
                    ordered.add(new ModuleWorkCost(c.dir(), c.prereqs(), c.weight(), c.testWeight()));
                }
            }
        }
        // Any leftover (shouldn't happen) — append in original cost order.
        for (EffortWeights.ModuleCost c : byDir.values()) {
            ordered.add(new ModuleWorkCost(c.dir(), c.prereqs(), c.weight(), c.testWeight()));
        }
        return ordered;
    }

    static List<EffortWeights.ModuleCost> etaCostsFromExplainPlan(
            ExplainPlan plan,
            Path cache,
            int workers,
            Path jdksDir,
            String profile,
            boolean skipTests,
            boolean verbose) {
        Set<Path> projectModules = new HashSet<>();
        for (TaskForecast.Module m : plan.modules()) projectModules.add(m.dir());
        List<String> projectDirs = projectModules.stream().map(Path::toString).toList();
        boolean distrust = SessionContext.current().config().forceOr(false)
                || SessionContext.current().config().rebuildOr(false);
        BuildMetrics metrics = BuildMetrics.load(BuildMetrics.defaultFile());
        StepTimings timings = StepTimings.load(cache);
        // Same jobs budget the live runner uses for -w auto (not raw availableProcessors alone).
        int jobsBudget = Math.max(1, cc.jumpkick.test.TestWorkers.effectiveJobs());
        List<EffortWeights.ModuleCost> costs = new ArrayList<>();
        for (TaskForecast.Module m : plan.modules()) {
            if (!distrust && !m.dirty()) continue;
            Path mdir = m.dir();
            Set<Path> prereqs = plan.edges().getOrDefault(mdir, Set.of());
            // Local *compile* content only — resource drift must not unlock suite walls (core's
            // "extra resources changed" was pricing ~792 tests while live only re-copied).
            boolean localCompile = hasLocalCompileContent(m);
            boolean resourceDrift = hasResourceDriftWork(m);
            // Native/assembly in the forecast keeps run-tests full (cli ← engine test-dep) even
            // when native itself is cascade-discounted below.
            boolean keepFullTests = localCompile
                    || hasHeavyPackagingTail(m)
                    || m.steps().stream().noneMatch(s -> (distrust || !s.cached()) && isCompileStepName(s.name()));
            List<String> running = new ArrayList<>();
            int cascadeRecheck = 0;
            for (TaskForecast.Task s : m.steps()) {
                if (!distrust && s.cached()) continue;
                // Price material work only — bookkeeping steps (parse-build, stamps, …) are not
                // cache hits but must not inflate ETA toward a full monorepo wall.
                if (!distrust && TaskForecast.Module.isBookkeepingStep(s.name())) continue;
                if (!distrust && shouldDiscountCascadeStep(s, localCompile, resourceDrift, keepFullTests)) {
                    cascadeRecheck++;
                    continue;
                }
                running.add(s.name());
            }
            // Rebuild with an empty step list still means "all work" — fall back to plan shape.
            if (running.isEmpty() && distrust) {
                BuildPlanner.Inputs inputs = TaskForecaster.inputsFor(
                        mdir, cache, workers, jdksDir, profile, skipTests, verbose, projectModules);
                BuildPlan.Builder builder = BuildPlanner.coreBuilder(inputs, true);
                BuildPlanner.appendDeclaredTails(builder, inputs);
                for (cc.jumpkick.run.Task s : builder.build().steps()) running.add(s.name());
            }
            if (running.isEmpty()) {
                // Resource-only producer or pure cascade recheck — milliseconds, not suite walls.
                int w = Math.max(EffortWeights.TOKEN, cascadeRecheck + (m.dirty() ? 1 : 0));
                costs.add(EffortWeights.costOf(mdir, prereqs, w, 0));
                continue;
            }
            java.util.Map<String, Integer> counts = new java.util.HashMap<>();
            if (m.testCount() > 0) counts.put("run-tests", m.testCount());
            if (m.sourceCount() > 0) {
                counts.put("compile-java", m.sourceCount());
                counts.put("compile-test", m.sourceCount());
            }
            int classGuess = m.testCount() > 0 ? Math.max(1, m.testCount() / 3) : 0;
            // workers: 0 = auto (same as bare jk build -w omit)
            int testW = cc.jumpkick.test.TestWorkers.resolve(workers, classGuess, jobsBudget);
            EffortWeights.ModuleCost priced = EffortWeights.costFromRunningSteps(
                    mdir, prereqs, running, metrics, timings, projectDirs, counts, testW);
            if (cascadeRecheck > 0) {
                priced = EffortWeights.costOf(mdir, prereqs, priced.weight() + cascadeRecheck, priced.testWeight());
            }
            costs.add(priced);
        }
        return costs;
    }

    /**
     * Steps that should not contribute full historical walls to open-loop ETA. Cascade-forced
     * compile/package/native and resource-only producers almost always action-cache hit for
     * compile/test; billing suite walls for them was the multi-minute dogfood miss.
     */
    static boolean shouldDiscountCascadeStep(
            TaskForecast.Task s, boolean localCompile, boolean resourceDrift, boolean keepFullTests) {
        if (s == null || s.cached()) return false;
        String name = s.name();
        // Cascade-forced compile/package without local source edits.
        if (!localCompile && isCascadeForcedStep(s) && isCompileOrPackageStep(name)) {
            return true;
        }
        // Cascade-forced native ("rebuild · compile changed") without local compile — cli native
        // often SKIPPED while tests still run (dogfood: priced ~34s native, actual SKIPPED).
        if (!localCompile && isCascadeForcedStep(s) && "native-image".equals(name)) {
            return true;
        }
        // Resource drift schedules copy/package only — never a full compile/test suite.
        if (!localCompile && resourceDrift && (isCompileStepName(name) || "run-tests".equals(name))) {
            return true;
        }
        // Pure cascade module: discount tests. Cli keeps tests when a heavy tail is forecast
        // (test-dep on a dirty engine) even if native itself is discounted.
        if (!localCompile && !keepFullTests && "run-tests".equals(name)) {
            return true;
        }
        return false;
    }

    /**
     * True when the module has real local compile content (sources/options/classpath) — not
     * resource drift alone, and not a zero-source partial.
     */
    static boolean hasLocalCompileContent(TaskForecast.Module m) {
        if (m == null || m.steps() == null) return false;
        for (TaskForecast.Task s : m.steps()) {
            if (s.cached() || !isCompileStepName(s.name())) continue;
            String t = s.text() == null ? "" : s.text();
            // "compile · 0 sources changed" is not material work.
            if (t.contains("0 source")) continue;
            if (s.status() == TaskForecast.Status.PARTIAL || s.status() == TaskForecast.Status.FULL) {
                return true;
            }
            if (t.contains("source changed")
                    || t.contains("sources")
                    || t.contains("no incremental")
                    || t.contains("classpath")
                    || t.contains("options")
                    || t.contains("not locked")
                    || t.contains("jk.toml")) {
                return true;
            }
        }
        return false;
    }

    /** Local compile content or resource drift (tests / call sites that need either). */
    static boolean hasLocalContentWork(TaskForecast.Module m) {
        return hasLocalCompileContent(m) || hasResourceDriftWork(m);
    }

    /** copy-resources / package-jar dirtied by resource drift (not compile cascade). */
    static boolean hasResourceDriftWork(TaskForecast.Module m) {
        if (m == null || m.steps() == null) return false;
        for (TaskForecast.Task s : m.steps()) {
            if (s.cached()) continue;
            if ("copy-resources".equals(s.name()) || "copy-test-resources".equals(s.name())) return true;
            String t = s.text() == null ? "" : s.text();
            if ("package-jar".equals(s.name()) && t.contains("resources changed")) return true;
        }
        return false;
    }

    /** Native / assembly / OCI tails — signal to keep full run-tests (cli-shaped test-dep). */
    static boolean hasHeavyPackagingTail(TaskForecast.Module m) {
        if (m == null || m.steps() == null) return false;
        return m.steps().stream()
                .anyMatch(s -> !s.cached()
                        && ("native-image".equals(s.name())
                                || "write-image".equals(s.name())
                                || "package-assembly".equals(s.name())));
    }

    /**
     * Forecast forced RUN because an upstream compile-scope sibling is dirty (action key still
     * hashed the pre-rebuild jar). Live keys usually hit when the upstream jar is byte-identical.
     */
    static boolean isCascadeForcedStep(TaskForecast.Task s) {
        if (s == null || s.cached()) return false;
        String t = s.text() == null ? "" : s.text();
        return t.contains("dependency changed") || t.contains("main changed") || t.contains("compile changed");
    }

    static boolean isCompileStepName(String name) {
        if (name == null) return false;
        return name.startsWith("compile-main")
                || name.startsWith("compile-java")
                || name.startsWith("compile-kotlin")
                || name.startsWith("compile-groovy")
                || name.startsWith("compile-test");
    }

    static boolean isCompileOrPackageStep(String name) {
        if (name == null) return false;
        return isCompileStepName(name) || "package-jar".equals(name) || "package-assembly".equals(name);
    }

    // =========================================================================
    // Build preflight (resolve + dirty forecast, for the fully-cached shortcut)
    // =========================================================================

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
    public static Set<Path> forecastDirtyDirs(ResolvedGraph graph, Path cache, boolean skipTests, Path entryDir) {
        return forecastDirtyDirs(graph.graph(), cache, skipTests, entryDir);
    }

    // =========================================================================
    // Workspace build (the front-end-callable event-emitting entry point)
    // =========================================================================

    private static final BuildPlanKey<TestSummary> TEST_RESULT = BuildPlanKey.of("test-result", TestSummary.class);

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
        // Re-lock when the workspace lock is stale so unsatisfiable deps fail here instead of
        // a false "all up to date" from per-module forecasts. Soft I/O failures don't block.
        if (req.freshenLock()) {
            Path rootLock = cc.jumpkick.lock.LockPaths.lockFile(req.entryDir());
            boolean lockStale = workspaceLockStale(req.entryDir(), req.entryBuild(), rootLock);
            if (lockStale) {
                // Countdown during lock: price lock + a coarse remaining-build prior so the TUI
                // does not pure count-up for the whole re-lock window. Remaining-work semantics —
                // the CLI converts via elapsed + remaining after each onEtaEstimate.
                long lockEta = estimateLockMillis(req.entryDir(), req.cache());
                long provisionalBuild = applyHistoryPrior(0, okHistory(req.entryDir()));
                if (provisionalBuild <= 0) {
                    provisionalBuild = EffortWeights.MS_PER_WEIGHT * 8L; // ~1.2s floor
                }
                listener.onEtaEstimate(lockEta + provisionalBuild);
            }
            listener.onPreflight("lock", 0, 0, lockStale ? "Refreshing workspace lock…" : "Workspace lock ready");
            LockGuard guard = ensureWorkspaceLockFresh(req.entryDir(), req.cache(), lockStale);
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
        // compare to prior structure memo before overwriting (fail-open).
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
        // Checking runs inside this build request (no separate client forecast RPC).
        // Client dirty hint (selection / force path) still avoids a second walk when provided.
        // --force/--redo short-circuits forecastDirtyDirs to "all" without per-step hashing.
        // when forecasting here, consult/store the local dirty memo under target/.jk/preflight/.
        Set<Path> dirty;
        Preflight preflight = null;
        if (req.dirtyHint() != null) {
            listener.onPreflight("checking", 0, 0, "Using dirty set…");
            dirty = req.dirtyHint();
            listener.onPreflight(
                    "checking", 1, 1, dirty.isEmpty() ? "Nothing dirty" : dirty.size() + " module(s) dirty");
        } else {
            listener.onPreflight("checking", 0, 0, "Checking cache…");
            preflight = forecastWithFingerprints(graph, req.cache(), req.skipTests(), req.entryDir());
            dirty = preflight.dirty();
            listener.onPreflight(
                    "checking", 1, 1, dirty.isEmpty() ? "All modules up to date" : dirty.size() + " module(s) dirty");
        }
        Perf.end("ws-forecast(hint=" + (req.dirtyHint() != null) + ",dirty=" + dirty.size() + ")", tf);

        // Each module's step durations feed one shared sink, folded into the learned ledger on success.
        List<StepTimings.Sample> timingSamples = Collections.synchronizedList(new ArrayList<>());
        List<HostLearnedRates.HostSample> hostSamples = Collections.synchronizedList(new ArrayList<>());
        // Host bootstrap + continuous priors for cold ETA (same as estimateEtaMillis).
        // Surface probe work on the build aggregate wedge (Calibrating host…).
        boolean probing = Calibration.needsProbe();
        if (probing) {
            listener.onPreflight("calibrate", 0, 1, "Calibrating host…");
        }
        Calibration.ensure(req.jdksDir());
        if (probing) {
            // complete=true drops the preflight row from the live tree (CommandManager.preflight).
            listener.onPreflight("calibrate", 1, 1, "Calibrating host…");
        }
        // only fully prepare modules that will execute (dirty). Clean modules skip prepare
        // and schedule — prepare is pure plan assembly (parse + plugin describe + step list);
        // real plugin work runs in steps. ensureMaterialized is idempotent CAS extract.
        List<BuildGraph.BuildUnit> dirtyUnits = new ArrayList<>();
        List<BuildGraph.BuildUnit> cleanUnits = new ArrayList<>();
        for (BuildGraph.BuildUnit u : units) {
            if (dirty.contains(u.dir())) dirtyUnits.add(u);
            else cleanUnits.add(u);
        }

        // ---- R0 seed: SAME path as jk explain (HARD INVARIANT) ----
        // One ExplainPlan + estimateEtaMillis — never a second divergent cost assembly.
        // When preflight already walked TaskForecaster, reuse those modules; otherwise explain.
        ExplainPlan etaPlan;
        boolean distrust = SessionContext.current().config().forceOr(false)
                || SessionContext.current().config().rebuildOr(false);
        if (preflight != null && !preflight.modules().isEmpty()) {
            // Same TaskForecaster walk already done for dirty-set — do not re-walk.
            etaPlan = new ExplainPlan(preflight.modules(), graph.edges(), graph.maxReadyWidth(), List.of());
        } else if (dirtyUnits.isEmpty() && !distrust) {
            // Fully cached — same as explain's empty-memo fast path.
            etaPlan = fullyCachedExplainPlan(graph);
        } else {
            // Memo hit with dirty set but no modules, or force/rebuild: one explain walk.
            etaPlan = explainFromGraph(graph, req.cache(), req.skipTests());
        }
        if (req.dirtyHint() != null) {
            etaPlan = restrictToSelection(etaPlan, dirty);
        }
        EtaModel etaModel = estimateEtaModel(
                etaPlan,
                req.entryDir(),
                req.cache(),
                req.workers(),
                req.jdksDir(),
                req.profile(),
                req.skipTests(),
                req.verbose(),
                parallelTests,
                req.maxModuleConcurrency());
        long etaMs = etaModel.etaMs();
        List<ModuleWorkCost> ordered = orderCostsLikeUnits(dirtyUnits, etaModel.costs());
        listener.onWorkModel(WorkModel.of(etaMs, etaModel.concurrency(), etaModel.serial(), parallelTests, ordered));
        listener.onEtaEstimate(etaMs);

        long tp = Perf.start();
        int nPrepare = dirtyUnits.size();
        listener.onPreflight(
                "plan", 0, Math.max(nPrepare, 1), nPrepare == 0 ? "Nothing to prepare" : "Preparing modules…");
        Map<Path, ModulePlan> plans;
        try {
            plans = prepareModules(dirtyUnits, req, moduleDirs, listener, nPrepare, timingSamples, hostSamples);
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

        // Re-emit R0 after prepare (seed path; client freezes seed once execute starts; residual
        // still re-anchors the painted countdown mid-run).
        listener.onEtaEstimate(etaMs);

        // Workspace artifact links for the whole graph (clean modules still own jars from prior builds).
        Map<Path, Path> wsLinks = computeWorkspaceLinks(moduleDirs, req.entryDir());
        for (BuildGraph.BuildUnit u : cleanUnits) {
            linkModuleArtifacts(u.dir(), wsLinks);
        }

        List<ModuleOutcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        List<Double> observedRates = Collections.synchronizedList(new ArrayList<>());
        long tsched = Perf.start();
        long executeStartMs = System.currentTimeMillis();
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
                        return null;
                    },
                    req.maxModuleConcurrency());
        }
        Perf.end("ws-schedule-run", tsched);
        long executeWallMs = Math.max(0L, System.currentTimeMillis() - executeStartMs);
        // Session cancel (Ctrl-C / jk cancel / web) may finish modules with a non-success exit
        // without a distinct flag — fold SessionCancel into the aggregate so clients settle as
        // cancelled rather than a generic failure.
        boolean cancelled = cc.jumpkick.run.SessionCancel.cancelled();
        boolean ok = failure == null && !cancelled;
        if (ok) {
            // Primary seed-quality KPI: |R0 − execute wall| / wall (never improved by residual).
            logSeedQuality(etaMs, executeWallMs, dirtyUnits.size());
            // Fold this run's step durations + measured throughput into the learned ledger + host
            // calibration (EWMA) so the next build's estimate is time-accurate. Failed and cancelled
            // builds never train — truncated walls poison ETA priors.
            StepTimings.record(req.cache(), timingSamples, StepTimings.DEFAULT_ALPHA, System.currentTimeMillis());
            Calibration.learnFromSuccess(List.copyOf(hostSamples));
            Double runMpw = medianRate(observedRates);
            if (runMpw != null) Calibration.refine(runMpw, System.currentTimeMillis());
            // after a successful full forecast path, store an all-clean dirty
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
        WorkspaceResult result = new WorkspaceResult(ok, exit, List.copyOf(outcomes), List.of(), cancelled);
        listener.onWorkspaceFinish(result);
        return result;
    }

    /**
     * Prepare plans for dirty modules only. When more than one module needs prepare and
     * {@code JK_PREPARE_PARALLEL} is not {@code false}, prepares in parallel on {@link JkThreads#io}
     * . Dirty modules always {@code forceRebuild} the plan assembly path.
     */
    private static Map<Path, ModulePlan> prepareModules(
            List<BuildGraph.BuildUnit> dirtyUnits,
            WorkspaceRequest req,
            Set<Path> moduleDirs,
            WorkspaceBuildListener listener,
            int nPrepare,
            List<StepTimings.Sample> timingSamples,
            List<HostLearnedRates.HostSample> hostSamples) {
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
                p.plan().addListener(timingsRecorder(p, timingSamples, hostSamples));
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
                        p.plan().addListener(timingsRecorder(p, timingSamples, hostSamples));
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
     * countdown. Costs are already Σ of dirty-step weights (measured step walls preferred). Schedule
     * composes them with concurrency / serial-test bounds. Whole-build history is only a cold seed
     * when the schedule has no costs — never a substitute for step composition.
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
            // No material dirty work in the forecast — ETA is 0 (cache verify only).
            // NEVER fall back to whole-build history here: that produced a phantom multi-minute
            // seed whenever every step was CACHED / bookkeeping-only, while `jk explain` hid the
            // lie behind "Fully Cached / <1s". History floors apply only when there is real work.
            return 0;
        }
        // Always convert at MS_PER_WEIGHT. Costs are priced either as absolute step walls
        // (flatWeight(avgMs) round-trips with ×150) or residual/static units trained in that same
        // reference frame.
        BuildMetrics.Stats okHist = okHistory(entryDir, hist);
        // Whole-build history floor only for true full rebuilds — not merely "many modules are
        // dirty." Wide+shallow forecasts (cascade dirties 20+ modules but only 1–2 schedule real
        // tests/native) used to hit dirtyModules>=16 and get floored to multi-minute full-rebuild
        // walls (~40s over on dogfood when most run-tests SKIPPED). Require substantial scheduled
        // weight breadth, or an explicit --force/--rebuild.
        boolean fullWork = isFullWorkShape(hist, costs);
        boolean coldFull = fullWork && (okHist == null || okHist.count() == 0);
        int etaConcurrency = concurrency;
        if (coldFull && !serial && concurrency > 1) {
            // ~75% of jobs: monorepo rebuilds rarely sustain full -j throughput.
            etaConcurrency = Math.max(1, (int) Math.ceil(concurrency * 0.75));
        }
        long base =
                EffortWeights.scheduleMillis(costs, etaConcurrency, serial, parallelTests, EffortWeights.MS_PER_WEIGHT);
        if (coldFull && base > 0) {
            base = Math.round(base * 1.08);
        }
        if (fullWork) {
            BuildMetrics.Stats plainFull = okHistory(entryDir, new HistoryShape(false, hist.dirtyModules()));
            BuildMetrics.Stats floorSrc = higherAvg(okHist, plainFull);
            if (floorSrc != null && floorSrc.count() > 0) {
                long floor = floorSrc.avgMillis();
                if (floorSrc.count() >= 2 && floorSrc.maxMillis() > floor) {
                    floor = hist.rebuild()
                            ? Math.round(0.25 * floorSrc.avgMillis() + 0.75 * floorSrc.maxMillis())
                            : Math.round(0.5 * floorSrc.avgMillis() + 0.5 * floorSrc.maxMillis());
                }
                if (floor > base) base = floor;
            }
        }
        // One-sided clamp for absurd over-estimates only (never pull incremental work up to history).
        // Then a tiny open-loop preference for mild over-estimate (finishing early feels worse than late).
        return preferSlightOverEstimate(applyHistoryPrior(base, okHist));
    }

    /**
     * Open-loop R0 prefers a hair high over a hair low. Pure {@code ×1.01} on non-zero seeds —
     * enough to absorb small schedule/bookkeeping under-shoot without the multi-minute floors we
     * removed. Intentionally not ~2.5% (that overshoots the product budget on mid-length builds).
     */
    static final double OPEN_LOOP_OVER_ESTIMATE = 1.01;

    static long preferSlightOverEstimate(long baseMs) {
        if (baseMs <= 0) return baseMs;
        return Math.round(baseMs * OPEN_LOOP_OVER_ESTIMATE);
    }

    /** Prefer the stats row with the higher successful average (and samples). */
    private static BuildMetrics.Stats higherAvg(BuildMetrics.Stats a, BuildMetrics.Stats b) {
        if (a == null || a.count() == 0) return b;
        if (b == null || b.count() == 0) return a;
        return a.avgMillis() >= b.avgMillis() ? a : b;
    }

    /**
     * Whether to floor ETA against whole-build invocation history.
     *
     * <ul>
     *   <li>{@code --force}/{@code --rebuild} — always (list-scheduling under-shoots contention)
     *   <li>Otherwise: many dirty modules <em>and</em> several with substantial scheduled weight
     *       (not bookkeeping-only cascade width)
     * </ul>
     *
     * <p>Weight threshold ≈ 5s at {@link EffortWeights#MS_PER_WEIGHT} so token/parse modules do not
     * count as "deep." Need ≥8 such modules so a 2-module test+native hot path does not inherit a
     * 28-module full-rebuild floor.
     */
    static boolean isFullWorkShape(HistoryShape hist, List<EffortWeights.ModuleCost> costs) {
        if (hist != null && hist.rebuild()) return true;
        int dirty = hist == null ? 0 : hist.dirtyModules();
        if (dirty < 16) return false;
        return substantialModuleCount(costs) >= 8;
    }

    /**
     * Modules whose scheduled weight exceeds ~5s wall ({@code MS_PER_WEIGHT × 34 ≈ 5.1s}). Token and
     * bookkeeping-only costs sit far below this.
     */
    static int substantialModuleCount(List<EffortWeights.ModuleCost> costs) {
        if (costs == null || costs.isEmpty()) return 0;
        int thr = Math.max(10, 5_000 / EffortWeights.MS_PER_WEIGHT);
        int n = 0;
        for (EffortWeights.ModuleCost c : costs) {
            if (c != null && c.weight() >= thr) n++;
        }
        return n;
    }

    /**
     * Log seed quality for dogfood / diagnosis. Always when {@code JK_ETA_SEED_LOG} is set; on large
     * relative error when Perf is enabled. Residual mid-run updates must not hide this KPI.
     */
    static void logSeedQuality(long seedMs, long actualExecuteMs, int dirtyModules) {
        if (seedMs <= 0 || actualExecuteMs <= 0) return;
        double ratio = (double) seedMs / (double) actualExecuteMs;
        double relErr = Math.abs(seedMs - actualExecuteMs) / (double) actualExecuteMs;
        boolean verbose = "1".equals(System.getenv("JK_ETA_SEED_LOG"))
                || "true".equalsIgnoreCase(System.getenv("JK_ETA_SEED_LOG"))
                || Perf.ENABLED;
        // Always note serious misses so they show up in engine logs without env.
        boolean serious = relErr >= 0.35 && actualExecuteMs >= 5_000L;
        if (!verbose && !serious) return;
        System.err.printf(
                "jk: eta-seed quality R0=%dms actual=%dms ratio=%.2f relErr=%.0f%% dirty=%d%n",
                seedMs, actualExecuteMs, ratio, relErr * 100.0, dirtyModules);
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
     * Whole-build history is a <em>cold seed only</em> (base=0) or an absurd-overestimate clamp.
     * Normal ETA is Σ dirty step walls from {@link BuildMetrics} — never blend toward a full-build
     * average, and never distinguish {@code build} vs {@code build:rebuild} for the schedule sum.
     * Success-only stats: failed/cancelled runs have abnormal durations.
     */
    static long applyHistoryPrior(long base, BuildMetrics.Stats okHist) {
        return applyHistoryPrior(base, okHist, false);
    }

    /** @param rebuildShape ignored — kept for call-site compatibility; step composition owns ETA. */
    static long applyHistoryPrior(long base, BuildMetrics.Stats okHist, boolean rebuildShape) {
        return applyHistoryPrior(base, okHist, rebuildShape, -1);
    }

    /**
     * @param rebuildShape ignored (API compat)
     * @param dirtyModules ignored (API compat)
     */
    static long applyHistoryPrior(long base, BuildMetrics.Stats okHist, boolean rebuildShape, int dirtyModules) {
        if (okHist == null || okHist.count() == 0) return base;
        if (base == 0) return okHist.avgMillis();
        // One-sided clamp for absurd over-estimates only. Require a credible history max so a
        // sub-second mis-keyed no-op sample (e.g. 99ms "full monorepo") cannot collapse a
        // composed multi-minute ETA to <1s.
        long max = okHist.maxMillis();
        if (okHist.count() >= 3 && max >= 5_000L && base > 2 * max) return 2 * max;
        return base;
    }

    /**
     * Successful build invocation stats with shape-aware keys.
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
            // reads a never-written key.
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
     * Assemble one dirty module's plan + estimates. Only called for modules in the dirty set
     * ; clean modules never enter here. {@code forceRebuild} seeds {@link
     * EffortWeights#predict} so bar weights don't collapse as fully-cached for upstream-dirty work.
     * Dirty modules are never fully-cached for plan purposes; {@link BuildPlanner#coreBuilder}
     * still predicts weights once via its lazy plan supplier.
     *
     * <p>when the static plan shape fingerprint is warm and the session is not
     * force/rebuild, reuse memoized {@code estimatedTotalWeight} (skip the parallel step estimate).
     */
    private static ModulePlan prepareModule(
            BuildGraph.BuildUnit u, WorkspaceRequest req, Set<Path> moduleDirs, boolean forceRebuild) {
        Path dir = u.dir();
        Path buildFile = dir.resolve("jk.toml");
        if (!Files.exists(buildFile)) return null;
        BuildPlanner.Inputs inputs = TaskForecaster.inputsFor(
                        dir,
                        req.cache(),
                        req.workers() > 0 ? req.workers() : 1,
                        req.jdksDir(),
                        req.profile(),
                        req.skipTests(),
                        req.verbose(),
                        moduleDirs,
                        req.testOnly())
                .withVariant(req.variant(), req.clientEnv())
                .withEphemeralActions(req.ephemeralActions());
        BuildPlan.Builder b = BuildPlanner.coreBuilder(inputs, forceRebuild);
        BuildPlanner.appendDeclaredTails(b, inputs);
        BuildPlan plan = b.build();
        // Bar weight must be live estimatedTotalWeight for dirty prepares: shape-memo weights ignore
        // source/upstream freshness and under-counted native-image (SKIP while Graal still runs).
        // Over-reserve tails so native/assembly/OCI reserve full learned walls up front when this
        // module is dirty (forceRebuild) — reweight may shrink on cache hit, never grow the bar.
        int weight = forceRebuild
                ? EffortWeights.withOverReserveTails(plan::estimatedTotalWeight)
                : plan.estimatedTotalWeight();
        boolean distrust = SessionContext.current().config().forceOr(false)
                || SessionContext.current().config().rebuildOr(false);
        if (!distrust && !forceRebuild) {
            // Clean / ETA-only shape memo is optional; dirty path above never uses it for weight.
            var shapeHit = PreflightMemo.tryLoadShape(req.entryDir(), dir, req.skipTests());
            if (shapeHit.isPresent()) {
                weight = shapeHit.get().weight();
                if (Perf.ENABLED) {
                    System.err.println("[jk-perf] shape-memo hit " + u.coord() + " weight=" + weight);
                }
            } else {
                PreflightMemo.storeShape(req.entryDir(), dir, req.skipTests(), PreflightMemo.shapeOf(plan, weight));
            }
        } else if (!distrust) {
            // Refresh shape outline from the live over-reserved weight for future ETA-only hits.
            PreflightMemo.storeShape(req.entryDir(), dir, req.skipTests(), PreflightMemo.shapeOf(plan, weight));
        }
        // Dirty ⇒ not fullyCached for calibration / skip-rate sampling.
        return new ModulePlan(u.dir(), u.coord(), plan, weight, false, req.cache());
    }

    /**learn run-tests rates from actual TestSummary counts when present. */
    private static StepTimingsRecorder timingsRecorder(
            ModulePlan p, List<StepTimings.Sample> timingSamples, List<HostLearnedRates.HostSample> hostSamples) {
        return new StepTimingsRecorder(
                p.dir().toString(),
                timingSamples,
                () -> p.plan().get(BuildPlanner.TEST_RESULT).orElse(null),
                hostSamples);
    }

    /** Run one module's plan, attaching the caller's per-module listener; map the result to an outcome. */
    private static ModuleOutcome runModule(ModulePlan module, WorkspaceBuildListener listener) {
        BuildPlanListener ml = listener.onModuleStart(module);
        if (ml != null) module.plan().addListener(ml);
        long t0 = System.nanoTime();
        try {
            // Same over-reserve as prepare: BuildPlan.run() re-evaluates step weights into its
            // denominator. Without this, nativeWeight can still return SKIP (binary looks fresh
            // vs pre-build jar) and the bar completes before Graal runs. Shrink via reweight on
            // cache hit; never grow the bar mid-run.
            BuildPlanResult r = EffortWeights.withOverReserveTails(module.plan()::run);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            int exit = r.success() ? 0 : exitCodeFor(module.plan());
            // Failures always count as work; successes count only when a productive step ran
            // (not pure cache hits / no-ops —.
            boolean didWork = !r.success() || moduleDidWork(r);
            ModuleOutcome o = new ModuleOutcome(module.coord(), module.dir(), r.success(), exit, ms, didWork);
            listener.onModuleFinish(o);
            return o;
        } catch (RuntimeException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            ModuleOutcome o = new ModuleOutcome(module.coord(), module.dir(), false, 1, ms, true);
            listener.onModuleFinish(o);
            return o;
        }
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
                || name.equals(cc.jumpkick.run.TaskNames.RUN_TESTS)
                || name.startsWith("package")
                || name.startsWith("native")
                || name.startsWith("write-image")
                || name.startsWith("image-")
                || name.contains("ksp")
                || name.startsWith("transform");
    }

    /** Test failures exit 4; every other plan failure exits 1. */
    private static int exitCodeFor(BuildPlan plan) {
        TestSummary tr = plan.get(TEST_RESULT).orElse(null);
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
