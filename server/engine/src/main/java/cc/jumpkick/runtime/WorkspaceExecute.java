// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ModuleOrder;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceCone;
import cc.jumpkick.engine.plugin.HeapPlan;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Linking;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.run.SessionCancel;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.jspecify.annotations.NullMarked;

/**
 * Workspace execute path for {@link BuildService}: artifact links, module prepare, schedule,
 * and per-module run. Presentation stays in the caller.
 */
@NullMarked
public final class WorkspaceExecute {

    private WorkspaceExecute() {}

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
            Path buildFile = moduleDir.resolve(ManifestPaths.MANIFEST);
            if (!Files.exists(buildFile)) continue;
            JkBuild build;
            try {
                build = JkBuildParser.parse(buildFile);
            } catch (Exception ignored) {
                continue;
            }
            BuildLayout layout = BuildLayout.of(wsRoot, moduleDir, build);
            if (!layout.packagedAtRoot()) continue;
            List<Path> candidates = new ArrayList<>();
            candidates.add(layout.mainJar());
            candidates.add(layout.assemblyJar());
            candidates.add(layout.nativeBinary());
            candidates.add(layout.nativeLibrary());
            candidates.add(layout.ociImageTar());
            // Each jar's sidecar POM travels with it. package-jar writes one beside the module's
            // own jar precisely so a workspace-built worker can be forked with its runtime closure
            // (PlannerPackage.writeSidecarPom), but the fork is handed the copy surfaced HERE — and
            // a jar surfaced without its POM has no closure at all. That is how a first-party
            // worker came to be launched with only its own classes on the classpath, dying on the
            // first third-party type it touched.
            candidates.add(sidecarPom(layout.mainJar()));
            candidates.add(sidecarPom(layout.assemblyJar()));
            moduleArtifacts.put(normalDir, candidates);
            moduleGroup.put(normalDir, build.project().group());
        }
        // Count per filename across all modules to detect collisions.
        Map<String, Long> filenameCounts = new HashMap<>();
        for (List<Path> arts : moduleArtifacts.values()) {
            for (Path art : arts) filenameCounts.merge(art.getFileName().toString(), 1L, Long::sum);
        }
        // Build the final src→linkDest map.
        Path wsTarget = wsRoot.resolve(BuildLayout.TARGET);
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
     * The {@code .pom} that sits beside {@code jar} — the spelling {@link
     * cc.jumpkick.runtime.PlannerPackage#writeSidecarPom} writes and {@code
     * PomRuntimeClasspath.siblingPom} reads. Collision renaming applies the same group prefix to
     * both names, so a renamed jar keeps a correctly-named POM beside it.
     */
    private static Path sidecarPom(Path jar) {
        String name = jar.getFileName().toString();
        return jar.resolveSibling(
                name.endsWith(".jar") ? name.substring(0, name.length() - 4) + ".pom" : name + ".pom");
    }

    // =========================================================================
    // Workspace build (the front-end-callable event-emitting entry point)
    // =========================================================================

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
        JkBuild entryBuild;
        try {
            entryBuild = JkBuildParser.parse(req.entryDir().resolve(ManifestPaths.MANIFEST));
        } catch (Exception e) {
            WorkspaceResult r = new WorkspaceResult(false, 2, List.of(), List.of(Errors.text(e)));
            listener.onWorkspaceFinish(r);
            return r;
        }
        // Re-lock when the workspace lock is stale so unsatisfiable deps fail here instead of
        // a false "all up to date" from per-module forecasts. Soft I/O failures don't block.
        if (req.freshenLock()) {
            Path rootLock = LockPaths.lockFile(req.entryDir());
            boolean lockStale = WorkspaceLock.workspaceLockStale(req.entryDir(), entryBuild, rootLock);
            if (lockStale) {
                // Countdown during lock: price lock + a coarse remaining-build prior so the TUI
                // does not pure count-up for the whole re-lock window. Remaining-work semantics —
                // the CLI converts via elapsed + remaining after each onEtaEstimate.
                long lockEta = WorkspaceLock.estimateLockMillis(req.entryDir(), req.cache());
                // Do not seed from full-build history here — that flashes multi-minute ETAs on
                // restore-shaped runs (clean → build). Use a small lock+floor provisional only.
                long provisionalBuild = EffortWeights.MS_PER_WEIGHT * 8L; // ~1.2s floor
                listener.onEtaEstimate(lockEta + provisionalBuild);
            }
            listener.onPreflight("lock", 0, 0, lockStale ? "Refreshing workspace lock…" : "Workspace lock ready");
            BuildService.LockGuard guard =
                    WorkspaceLock.ensureWorkspaceLockFresh(req.entryDir(), req.cache(), lockStale);
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
            graph = BuildGraph.resolve(req.entryDir(), entryBuild);
        } catch (IOException e) {
            WorkspaceResult r = new WorkspaceResult(false, 2, List.of(), List.of(Errors.text(e)));
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
        // Selection cone (native/image/-m): filter before dirty forecast and ETA.
        graph = applySelectionCone(graph, req);
        units = graph.topoOrder();
        if (units.isEmpty()) {
            // A NON-EMPTY selection that matches nothing is an error, not a clean no-op —
            // success(0) here silently "built" a mistyped -m selection.
            WorkspaceSpec spec = req.spec();
            if (spec != null && spec.hasSelection()) {
                String sel = spec.selectedModules().stream()
                        .map(Path::toString)
                        .sorted()
                        .collect(Collectors.joining(", "));
                WorkspaceResult r = new WorkspaceResult(
                        false, 2, List.of(), List.of("selection matched no workspace module: " + sel));
                listener.onWorkspaceFinish(r);
                return r;
            }
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
            JvmOptions.planAndApply(HeapPlan.requestedJvms(width, effectiveWorkers(req), parallelTests, cap));
        }

        Set<Path> moduleDirs = new LinkedHashSet<>();
        for (BuildGraph.BuildUnit u : units) moduleDirs.add(u.dir());
        // Modules some entered module depends on: dependents' compile classpath consumes their
        // JAR (WorkspaceClasspath), so even test-only plans must package them — a testOnly plan
        // for a consumed prereq recompiled classes but left the jar stale, and dependents
        // compiled (and green-tested) against old code (JK-2177).
        Set<Path> jarConsumed = new LinkedHashSet<>();
        for (Set<Path> prereqs : graph.edges().values()) {
            for (Path p : prereqs) jarConsumed.add(BuildGraph.canonicalPath(p));
        }
        long tf = Perf.start();
        // Checking runs inside this build request (no separate client forecast RPC).
        // Client dirty hint (selection / force path) still avoids a second walk when provided.
        // --force/--redo short-circuits forecastDirtyDirs to "all" without per-step hashing.
        // when forecasting here, consult/store the local dirty memo under target/.jk/preflight/.
        Set<Path> dirty;
        Set<Path> restoreNeeded = Set.of();
        BuildForecasting.Preflight preflight = null;
        if (req.dirtyHint() != null) {
            listener.onPreflight("checking", 0, 0, "Using dirty set…");
            // Client -m / cwd selection is the seed; expand to build prereqs so a selected
            // member is not scheduled without the siblings it compiles against.
            dirty = ModuleHints.withPrereqs(graph, req.dirtyHint());
            listener.onPreflight(
                    "checking", 1, 1, dirty.isEmpty() ? "Nothing dirty" : dirty.size() + " module(s) dirty");
        } else if (req.testOnly()) {
            // Workspace {@code jk test} with no client dirty hint: run every module in the
            // (already cone-filtered) graph — not a cache-forecast subset.
            listener.onPreflight("checking", 0, 0, "Testing all selected modules…");
            dirty = Set.copyOf(moduleDirs);
            listener.onPreflight("checking", 1, 1, dirty.size() + " module(s)");
        } else {
            listener.onPreflight("checking", 0, 0, "Checking cache…");
            preflight = BuildForecasting.forecastWithFingerprints(
                    graph, req.cache(), req.skipTests(), req.entryDir(), req.target(), terminalTargetDirs(units, req));
            dirty = preflight.dirty();
            restoreNeeded = preflight.restoreNeeded();
            String msg;
            if (dirty.isEmpty() && restoreNeeded.isEmpty()) msg = "All modules up to date";
            else if (dirty.isEmpty()) msg = restoreNeeded.size() + " module(s) restore";
            else if (restoreNeeded.isEmpty()) msg = dirty.size() + " module(s) dirty";
            else msg = dirty.size() + " dirty, " + restoreNeeded.size() + " restore";
            listener.onPreflight("checking", 1, 1, msg);
        }
        Perf.end(
                "ws-forecast(hint="
                        + (req.dirtyHint() != null)
                        + ",dirty="
                        + dirty.size()
                        + ",restore="
                        + restoreNeeded.size()
                        + ")",
                tf);

        // Inputs clean + outputs missing: restore from action cache, then treat as fully cached.
        if (dirty.isEmpty()
                && !restoreNeeded.isEmpty()
                && req.target() == WorkspaceTarget.PACKAGE
                && !SessionContext.current().config().rebuildOr(false)
                && !SessionContext.current().config().forceOr(false)) {
            listener.onPreflight("restore", 0, restoreNeeded.size(), "Restoring outputs…");
            long restoreEta = (long) EffortWeights.RESTORE * EffortWeights.MS_PER_WEIGHT * restoreNeeded.size();
            listener.onEtaEstimate(restoreEta);
            List<Path> failed;
            try {
                failed = ModuleOutputRestore.restoreAll(req.entryDir(), List.copyOf(restoreNeeded), req.cache());
            } catch (IOException e) {
                WorkspaceResult r = new WorkspaceResult(false, 2, List.of(), List.of(Errors.text(e)));
                listener.onWorkspaceFinish(r);
                return r;
            }
            listener.onPreflight("restore", restoreNeeded.size(), restoreNeeded.size(), "Restoring outputs…");
            if (!failed.isEmpty()) {
                // Action-cache miss: fall through to normal RUN for those modules only.
                dirty = new LinkedHashSet<>(failed);
            } else {
                Map<Path, Path> wsLinks = computeWorkspaceLinks(moduleDirs, req.entryDir());
                for (BuildGraph.BuildUnit u : units) {
                    linkModuleArtifacts(u.dir(), wsLinks);
                }
                if (req.dirtyHint() == null && !req.testOnly()) {
                    Map<Path, String> fps =
                            preflight != null && !preflight.fingerprints().isEmpty()
                                    ? preflight.fingerprints()
                                    : PreflightMemo.snapshotFingerprints(graph, req.skipTests());
                    if (!fps.isEmpty()) {
                        PreflightMemo.storeDirty(req.entryDir(), graph, req.skipTests(), Set.of(), fps);
                    }
                }
                listener.onEtaEstimate(0);
                WorkspaceResult r = new WorkspaceResult(true, 0, List.of(), List.of());
                listener.onWorkspaceFinish(r);
                return r;
            }
        }

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
            // complete=true drops the preflight row from the live tree (JkManager.preflight).
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
            etaPlan = BuildForecasting.fullyCachedExplainPlan(graph);
        } else {
            // Memo hit with dirty set but no modules, or force/rebuild: one explain walk.
            etaPlan = BuildForecasting.explainFromGraph(graph, req.cache(), req.skipTests());
            // explainFromGraph uses package tails; native/image extra work is in the live
            // prepare weights. Dirty set already used target-aware forecast above.
        }
        if (req.dirtyHint() != null) {
            etaPlan = BuildForecasting.restrictToSelection(etaPlan, dirty);
        }
        BuildService.EtaModel etaModel = BuildEta.estimateEtaModel(
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
        List<ModuleWorkCost> ordered = BuildEta.orderCostsLikeUnits(dirtyUnits, etaModel.costs());
        listener.onWorkModel(WorkModel.of(etaMs, etaModel.concurrency(), etaModel.serial(), parallelTests, ordered));
        listener.onEtaEstimate(etaMs);

        long tp = Perf.start();
        int nPrepare = dirtyUnits.size();
        listener.onPreflight(
                "plan", 0, Math.max(nPrepare, 1), nPrepare == 0 ? "Nothing to prepare" : "Preparing modules…");
        Map<Path, ModulePlan> plans;
        try {
            plans = prepareModules(
                    dirtyUnits, req, moduleDirs, jarConsumed, listener, nPrepare, timingSamples, hostSamples);
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

        if (SessionCancel.cancelled()) {
            WorkspaceResult r = new WorkspaceResult(false, 1, List.of(), List.of(), true);
            listener.onWorkspaceFinish(r);
            return r;
        }

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
                    (u, artifactsReady) -> runModule(plans.get(u.dir()), listener, artifactsReady),
                    (ready, results, _) -> {
                        for (int i = 0; i < results.size(); i++) {
                            ModuleOutcome o = results.get(i);
                            outcomes.add(o);
                            // Fail-fast unless the caller asked for the whole graph. Keeping going
                            // is safe because admission keys on artifacts-ready, not completion: a
                            // module whose tests failed has already published what its dependents
                            // compile against, and one that failed before packaging publishes
                            // nothing, so its dependents fail on their own accurate account
                            // rather than on this one's behalf.
                            if (!o.success() && !req.keepGoing()) return o;
                            linkModuleArtifacts(ready.get(i).dir(), wsLinks);
                            ModulePlan p = plans.get(ready.get(i).dir());
                            // Skip fully-cached modules — their near-zero time isn't representative of work.
                            if (p != null && !p.fullyCached() && p.weight() > 0 && o.millis() > 0)
                                observedRates.add(o.millis() / (double) p.weight());
                        }
                        return null;
                    },
                    req.maxModuleConcurrency(),
                    SessionCancel::cancelled);
        }
        Perf.end("ws-schedule-run", tsched);
        long executeWallMs = Math.max(0L, System.currentTimeMillis() - executeStartMs);
        // Session cancel (Ctrl-C / jk cancel / web) may finish modules with a non-success exit
        // without a distinct flag — fold SessionCancel into the aggregate so clients settle as
        // cancelled rather than a generic failure.
        boolean cancelled = SessionCancel.cancelled();
        // Under keep-going the sink never returns, so the verdict comes from the outcomes: the
        // run still fails, and the exit code is the first failure's, exactly as fail-fast reports.
        ModuleOutcome firstFailure = failure;
        if (firstFailure == null) {
            for (ModuleOutcome o : outcomes) {
                if (!o.success()) {
                    firstFailure = o;
                    break;
                }
            }
        }
        boolean ok = firstFailure == null && !cancelled;
        if (ok) {
            // Primary seed-quality KPI: |R0 − execute wall| / wall (never improved by residual).
            BuildEta.logSeedQuality(etaMs, executeWallMs, dirtyUnits.size());
            // Fold the schedule-contention observation (actual vs the PRE-bias simulation) so
            // the next estimate prices this host's real overlap efficiency.
            ScheduleBias.observe(req.entryDir(), etaModel.rawScheduleMs(), executeWallMs, dirtyUnits.size());
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
            // Terminal targets (native/image/compile) never store: their graph may be
            // cone-restricted and their clean-claim (binary present, push performed) is not
            // what the memo's package-level check certifies — see BuildForecasting.memoSafe.
            if (req.dirtyHint() == null && !req.testOnly() && req.target() == WorkspaceTarget.PACKAGE) {
                Map<Path, String> fps = PreflightMemo.snapshotFingerprints(graph, req.skipTests());
                if (!fps.isEmpty()) {
                    PreflightMemo.storeDirty(req.entryDir(), graph, req.skipTests(), Set.of(), fps);
                }
            }
        }
        int exit = ok ? 0 : (cancelled ? 1 : firstFailure.exitCode());
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
            Set<Path> jarConsumed,
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
                if (SessionCancel.cancelled()) break;
                ModulePlan p = prepareModule(u, req, moduleDirs, jarConsumed, true);
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
            if (SessionCancel.cancelled()) break;
            futures.add(CompletableFuture.runAsync(
                    () -> {
                        if (SessionCancel.cancelled()) return;
                        ModulePlan p = prepareModule(u, req, moduleDirs, jarConsumed, true);
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
        return EnvValues.bool(System::getenv, "JK_PREPARE_PARALLEL").orElse(true);
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
            BuildGraph.BuildUnit u,
            WorkspaceRequest req,
            Set<Path> moduleDirs,
            Set<Path> jarConsumed,
            boolean forceRebuild) {
        Path dir = u.dir();
        Path buildFile = dir.resolve(ManifestPaths.MANIFEST);
        if (!Files.exists(buildFile)) return null;
        BuildPlan plan = assemblePlan(u, req, moduleDirs, forceRebuild, jarConsumed);
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

    /**
     * Restrict {@code graph} to the request's selected module cone. Empty selection = whole graph.
     * Tests on → all dependency scopes (so dirty test harnesses rebuild); {@code skipTests} →
     * production scopes only.
     */
    static BuildGraph.Result applySelectionCone(BuildGraph.Result graph, WorkspaceRequest req) {
        WorkspaceSpec spec = req.spec();
        if (spec == null || !spec.hasSelection()) return graph;
        Map<Path, JkBuild> byDir = new LinkedHashMap<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) byDir.put(u.dir(), u.manifest());
        var scopes = req.skipTests() ? ModuleOrder.PRODUCTION_SCOPES : List.of(Scope.values());
        Set<Path> cone = WorkspaceCone.expand(byDir, spec.selectedModules(), scopes);
        return graph.restrict(cone);
    }

    /**
     * The module dirs that will receive the target's terminal step — the SAME eligibility
     * {@link #assemblePlan} applies (selection membership; native additionally needs a resolvable
     * Graal home). The forecast consumes this so dirty prediction and plan assembly can never
     * disagree about which modules carry terminal work.
     */
    static Set<Path> terminalTargetDirs(List<BuildGraph.BuildUnit> units, WorkspaceRequest req) {
        WorkspaceTarget target = req.target();
        WorkspaceSpec spec = req.spec() == null ? WorkspaceSpec.DEFAULT : req.spec();
        // INSTALL publishes every module in the (already cone-filtered) graph — selected
        // terminals and production prereqs — so a worker POM can resolve sibling jars from
        // repos/jk-local. A coordinator root is the exception: it packages nothing, so
        // assemblePlan gives it no cache-install and the forecast must not expect one.
        // NATIVE/IMAGE keep selection-only terminals.
        if (target == WorkspaceTarget.INSTALL) {
            Set<Path> all = new LinkedHashSet<>();
            for (BuildGraph.BuildUnit u : units) {
                if (CompileSupport.coordinatorOnly(u.manifest(), u.dir())) continue;
                all.add(u.dir());
            }
            return all;
        }
        if (target != WorkspaceTarget.NATIVE && target != WorkspaceTarget.IMAGE) return Set.of();
        Set<Path> out = new LinkedHashSet<>();
        for (BuildGraph.BuildUnit u : units) {
            Path dir = u.dir();
            boolean selected = !spec.hasSelection()
                    || spec.selectedModules().stream()
                            .anyMatch(p -> BuildGraph.canonicalPath(p).equals(BuildGraph.canonicalPath(dir)));
            if (!selected) continue;
            if (target == WorkspaceTarget.NATIVE && GraalHomes.lookup(dir, spec.graalByDir()) == null) continue;
            out.add(dir);
        }
        return out;
    }

    static BuildPlan assemblePlan(
            BuildGraph.BuildUnit u, WorkspaceRequest req, Set<Path> moduleDirs, boolean forceRebuild) {
        return assemblePlan(u, req, moduleDirs, forceRebuild, Set.of());
    }

    static BuildPlan assemblePlan(
            BuildGraph.BuildUnit u,
            WorkspaceRequest req,
            Set<Path> moduleDirs,
            boolean forceRebuild,
            Set<Path> jarConsumed) {
        Path dir = u.dir();
        WorkspaceTarget target = req.target();
        WorkspaceSpec spec = req.spec() == null ? WorkspaceSpec.DEFAULT : req.spec();
        boolean selected = !spec.hasSelection()
                || spec.selectedModules().stream()
                        .anyMatch(p -> BuildGraph.canonicalPath(p).equals(BuildGraph.canonicalPath(dir)));
        UnaryOperator<BuildPlanner.Inputs> decorate = requestKnobs(req, moduleDirs);
        if (target == WorkspaceTarget.NATIVE) {
            Path graal = GraalHomes.lookup(dir, spec.graalByDir());
            boolean allowNative = selected && graal != null;
            return NativePlans.moduleBuildPlan(
                    dir,
                    u.manifest(),
                    req.cache(),
                    req.jdksDir(),
                    graal,
                    spec.nativeMain(),
                    spec.nativeExtraArgs(),
                    req.skipTests(),
                    req.verbose(),
                    allowNative,
                    decorate);
        }
        if (target == WorkspaceTarget.IMAGE && selected) {
            return ImagePlans.imageBuildPlan(
                    dir,
                    req.cache(),
                    req.jdksDir(),
                    req.skipTests(),
                    req.verbose(),
                    spec.imageMain(),
                    spec.imageRegistry(),
                    spec.imageTag(),
                    spec.imageTarball(),
                    spec.imageDocker(),
                    decorate);
        }
        if (target == WorkspaceTarget.COMPILE && selected) {
            // Unselected cone prereqs fall through to PACKAGE below: the selected module's
            // compile classpath consumes sibling JARS, so prereqs must package, not just compile.
            return CompilePlans.compileBuildPlan(dir, req.cache(), req.profile(), req.verbose(), decorate);
        }
        if (target == WorkspaceTarget.INSTALL) {
            Path graal = GraalHomes.lookup(dir, spec.graalByDir());
            BuildPlanner.Inputs inputs = moduleInputs(dir, req, moduleDirs, false);
            BuildPlan.Builder b = BuildPlanner.coreBuilder(inputs, forceRebuild);
            PlannerTails.appendDeclaredTails(b, inputs, graal, true);
            // A coordinator root publishes nothing of its own: its plan stops at the workspace's
            // build logic and has no package-jar for cache-install to require. Appending the
            // terminal anyway fails plan validation before any module starts — the same reason
            // appendDeclaredTails returns early for it.
            if (!CompileSupport.coordinatorOnly(u.manifest(), dir)) {
                // Client-resolved --m2-dir rides the spec; the engine daemon's own user.home is
                // the fallback, not the answer — its home is not necessarily the caller's.
                Path m2 = spec.m2Dir() != null ? spec.m2Dir() : Path.of(System.getProperty("user.home", "."), ".m2");
                InstallPlans.appendCacheInstall(b, u.manifest(), req.cache(), m2);
            }
            return b.build();
        }
        // A consumed prereq must package even on the test path: dependents compile against
        // its sibling JAR, not its classes dir (JK-2177).
        boolean consumed = jarConsumed.contains(BuildGraph.canonicalPath(dir));
        boolean testOnly = (target.testOnly() || req.testOnly()) && !consumed;
        BuildPlanner.Inputs inputs = moduleInputs(dir, req, moduleDirs, testOnly);
        BuildPlan.Builder b = BuildPlanner.coreBuilder(inputs, forceRebuild);
        if (!testOnly) PlannerTails.appendDeclaredTails(b, inputs);
        return b.build();
    }

    /**
     * The one spelling of the request knobs every module plan must carry — workers (clamped),
     * profile, project modules, variant + client env, ephemeral actions. Every terminal branch of
     * {@link #assemblePlan} applies this operator (the PACKAGE/INSTALL path via
     * {@link #moduleInputs}), so a branch cannot silently plan against a different manifest than
     * its siblings. The single-plan verbs build the same set from the request line
     * ({@code WorkspaceBuildVerb} and friends) — a different layer, not folded here.
     */
    static UnaryOperator<BuildPlanner.Inputs> requestKnobs(WorkspaceRequest req, Set<Path> moduleDirs) {
        return in -> in.withWorkerCount(effectiveWorkers(req))
                .withProfileName(req.profile())
                .withProjectModules(moduleDirs)
                .withVariant(req.variant(), req.clientEnv())
                .withEphemeralActions(req.ephemeralActions());
    }

    /** Request workers with the {@code 0 = auto} spelling clamped to one planned worker. */
    private static int effectiveWorkers(WorkspaceRequest req) {
        return req.workers() > 0 ? req.workers() : 1;
    }

    /**
     * One module's {@link BuildPlanner.Inputs} for the workspace walk — {@code inputsFor} plus
     * {@link #requestKnobs}, so the knob set is applied by the type rather than restated here.
     */
    static BuildPlanner.Inputs moduleInputs(Path dir, WorkspaceRequest req, Set<Path> moduleDirs, boolean testOnly) {
        return requestKnobs(req, moduleDirs)
                .apply(TaskForecaster.inputsFor(
                        dir,
                        req.cache(),
                        effectiveWorkers(req),
                        req.jdksDir(),
                        req.profile(),
                        req.skipTests(),
                        req.verbose(),
                        moduleDirs,
                        testOnly));
    }

    /**
     * Image-terminal outcome from the plan's structured keys ({@code null} for non-image plans) —
     * the same fields the single-plan path reads for {@code planFinishImage}, so the workspace
     * {@code jk image} chip can show the identical Pushed/Wrote/Loaded tail.
     */
    private static ModuleOutcome.Image imageOutcomeOf(BuildPlan plan) {
        var cfg = plan.get(ImagePlans.CONFIG).orElse(null);
        Path tarball = plan.get(ImagePlans.TARBALL_PATH).orElse(null);
        String ref = plan.get(ImagePlans.IMAGE_REF).orElse(null);
        if (cfg == null && tarball == null && ref == null) return null;
        var project = plan.get(BuildPlanner.PROJECT).orElse(null);
        boolean daemonMode = tarball == null
                && (cfg == null || cfg.registry() == null || cfg.registry().isBlank());
        String daemonExe =
                !daemonMode ? null : cfg != null && cfg.dockerExecutable() != null ? cfg.dockerExecutable() : "docker";
        return new ModuleOutcome.Image(
                ref,
                tarball != null ? tarball.toString() : null,
                project != null ? project.project().name() : null,
                project != null ? project.project().version() : null,
                daemonExe);
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
        return runModule(module, listener, () -> {});
    }

    /**
     * As {@link #runModule(ModulePlan, WorkspaceBuildListener)}, firing {@code artifactsReady}
     * the moment every artifact step other modules consume (package-jar, and package-assembly
     * when declared — {@code WorkspaceClasspath} sibling jars / {@code enrichCliTestProps}
     * assemblies) is terminal-ok. With packaging no longer gated on run-tests (JK-2211) that is
     * right after compile+resources, so dependents overlap this module's test suite (JK-2210).
     * A failed or absent artifact step never fires — the scheduler publishes on completion
     * instead, and fail-fast or the dependent's own "sibling not built" reports it.
     */
    private static ModuleOutcome runModule(
            ModulePlan module, WorkspaceBuildListener listener, Runnable artifactsReady) {
        BuildPlanListener ml = listener.onModuleStart(module);
        if (ml != null) module.plan().addListener(ml);
        watchArtifactSteps(module.plan(), artifactsReady);
        long t0 = System.nanoTime();
        try {
            // Same over-reserve as prepare: BuildPlan.run() re-evaluates step weights into its
            // denominator. Without this, nativeWeight can still return SKIP (binary looks fresh
            // vs pre-build jar) and the bar completes before Graal runs. Shrink via reweight on
            // cache hit; never grow the bar mid-run.
            BuildPlanResult r = EffortWeights.withOverReserveTails(module.plan()::run);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            boolean cancelled = r.userCancelled() || SessionCancel.cancelled();
            // NativePlans owns the full failure mapping (native main-class misconfig → USAGE,
            // test failure → 4, else 1) so jk native --main bad exits 64 like the old verb did.
            int exit = r.success() && !cancelled ? 0 : NativePlans.failureExitCode(module.plan(), r);
            // Failures always count as work; successes count only when a productive step ran
            // (not pure cache hits / no-ops —.
            boolean didWork = !r.success() || cancelled || BuildService.moduleDidWork(r);
            ModuleOutcome o = new ModuleOutcome(
                    module.coord(), module.dir(), r.success() && !cancelled, exit, ms, didWork, cancelled);
            ModuleOutcome.Image img = imageOutcomeOf(module.plan());
            if (img != null) o = o.withImage(img);
            listener.onModuleFinish(o);
            return o;
        } catch (RuntimeException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            boolean cancelled = SessionCancel.cancelled();
            ModuleOutcome o = new ModuleOutcome(module.coord(), module.dir(), false, 1, ms, true, cancelled);
            listener.onModuleFinish(o);
            return o;
        }
    }

    /** Fire {@code artifactsReady} once when all of the plan's artifact steps finish ok (JK-2210). */
    static void watchArtifactSteps(BuildPlan plan, Runnable artifactsReady) {
        Set<String> artifactSteps = new HashSet<>();
        for (Task step : plan.steps()) {
            // compile-test is an artifact too: kind=tests siblings consume this module's
            // classes/test (WorkspaceClasspath testClassesDir), and it never waits on the suite.
            // compile-test-fixtures is the same for fixtures = true consumers.
            if (TaskNames.PACKAGE_JAR.equals(step.name())
                    || TaskNames.PACKAGE_ASSEMBLY.equals(step.name())
                    || TaskNames.COMPILE_TEST.equals(step.name())
                    || TaskNames.COMPILE_TEST_FIXTURES.equals(step.name())) {
                artifactSteps.add(step.name());
            }
        }
        if (artifactSteps.isEmpty()) return; // no packaging or tests — publish on completion
        AtomicInteger remaining = new AtomicInteger(artifactSteps.size());
        plan.addListener(new BuildPlanListener() {
            @Override
            public void stepFinish(String step, String group, TaskStatus status, Duration duration) {
                if (!artifactSteps.contains(step)) return;
                if (status != TaskStatus.SUCCESS && status != TaskStatus.SKIPPED) {
                    return; // failed/cancelled artifact: stay unpublished
                }
                if (remaining.decrementAndGet() == 0) artifactsReady.run();
            }
        });
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
