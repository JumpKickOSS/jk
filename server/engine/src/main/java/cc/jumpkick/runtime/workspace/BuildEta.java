// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.Calibration;
import cc.jumpkick.runtime.EffortWeights;
import cc.jumpkick.runtime.PlannerTails;
import cc.jumpkick.runtime.TaskForecaster;
import cc.jumpkick.runtime.base.BuildMetrics;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.runtime.base.ScheduleBias;
import cc.jumpkick.runtime.base.StepTimings;
import cc.jumpkick.test.TestWorkers;
import cc.jumpkick.wire.runtime.ExplainPlan;
import cc.jumpkick.wire.runtime.ModuleWorkCost;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * ETA / history fold for {@link BuildService}: schedule seed, cascade discounts, and
 * success-only invocation priors. Explain and live build must share this path bit-for-bit.
 */
@NullMarked
public final class BuildEta {

    private BuildEta() {}

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
            @Nullable Path jdksDir,
            @Nullable String profile,
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

    public static BuildService.EtaModel estimateEtaModel(
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
        try {
            // Host calibration: cheap when present; bootstrap probe once when missing (network
            // unless --offline). Host scale then multiplies product baselines for cold steps.
            Calibration.ensure(jdksDir);
            List<EffortWeights.ModuleCost> costs = etaCostsFromExplainPlan(
                    plan, cache, workers, jdksDir, profile, skipTests, verbose, maxModuleConcurrency);
            int concurrency = etaConcurrency(maxModuleConcurrency);
            boolean serialEta = concurrency <= 1;
            if (costs.isEmpty()) return new BuildService.EtaModel(0, costs, concurrency, serialEta);
            Seed seed = seedEta(
                    entryDir,
                    costs,
                    costDirs(costs),
                    concurrency,
                    serialEta,
                    parallelTests,
                    cache,
                    jdksDir,
                    historyShapeForCosts(costs.size()));
            if (Perf.enabled()) {
                // The four numbers per module that decide the whole estimate: WorkSchedule admits
                // a dependent at the gate, so a collapsed split serializes the graph. Logged here
                // rather than reconstructed from a synthetic plan — a hand-built ExplainPlan
                // prices nothing like a real one and sends readers after the wrong suspect.
                for (EffortWeights.ModuleCost c : costs) {
                    Perf.note(
                            "eta-cost " + c.dir(),
                            "weight",
                            c.weight(),
                            "test",
                            c.testWeight(),
                            "tail",
                            c.tailWeight(),
                            "gate",
                            c.gateWeight());
                }
                Perf.note(
                        "eta",
                        "raw",
                        seed.rawScheduleMs(),
                        "final",
                        seed.etaMs(),
                        "concurrency",
                        concurrency,
                        "serial",
                        serialEta,
                        "bias",
                        ScheduleBias.current(entryDir, costs.size()));
            }
            return new BuildService.EtaModel(seed.etaMs(), costs, concurrency, serialEta, seed.rawScheduleMs());
        } catch (RuntimeException e) {
            // Never fail explain/build over the estimate — but do not silently advertise 0s/empty.
            Log.warn("jk: ETA estimate failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return BuildService.EtaModel.empty();
        }
    }

    /**
     * Recorded wall of the invocation root's build-logic scripts, or {@code 0} when the project has
     * none. Measured, never a static prior: a gate is arbitrary user script and a guessed duration
     * would be fiction. See {@link BuildLogicEffort} for the discovery and will-it-run rules; the
     * module-scoped anchors are priced into their own module, not here.
     */
    private static long rootBuildLogicMillis(Path entryDir) {
        boolean guardRequested = SessionContext.current().testSelection().guard()
                || SessionContext.current().testSelection().scriptsOnly();
        return BuildLogicEffort.rootMillis(entryDir, BuildMetrics.load(BuildMetrics.defaultFile()), guardRequested);
    }

    /**
     * Whether this module will really compete for the machine — the population the {@code -w} auto
     * share is divided by.
     *
     * <p>Not the same question as {@link TaskForecast.Module#dirty()}. On an incremental build most
     * of the tree is dirty in the bookkeeping sense and yet forks no JVM and compiles nothing: its
     * steps are all cache hits, or the only material one is restoring outputs the CAS already holds.
     * Dividing the machine by that count is how a one-file edit came to look twelve modules wide,
     * which cut every suite's runner share from 24 to 2 and priced {@code server/engine}'s suite at
     * 36 s against the 17 s it actually takes on 24 runners.
     *
     * <p>{@code BuildForecasting.isRestoreOnly} is the neighbouring predicate and does not answer
     * this: it looks for a restore step, so a module with <em>no</em> material work at all — the
     * common case here — comes back {@code false}.
     */
    static boolean competesForMachine(TaskForecast.Module m) {
        if (!m.dirty()) return false;
        for (TaskForecast.Task s : m.steps()) {
            if (s.cached()) continue;
            if (TaskForecast.Module.isBookkeepingStep(s.name())) continue;
            if (!TaskForecast.Module.isMaterialWork(s.name())) continue;
            if (TaskNames.RESTORE_OUTPUTS.equals(s.name())) continue;
            return true;
        }
        return false;
    }

    /**
     * Module-concurrency budget for the ETA schedule — the executor's own cap, so explain and the
     * live countdown clamp the same way: the request's {@code -j} when it carries one, else the
     * engine's resolved jobs (every core). Not the graph's ready width: the live scheduler admits a
     * dependent as soon as its prerequisites have compiled, so a build's in-flight count is bounded
     * by the cap and not by how many modules are ready under full-completion semantics — the
     * dogfood rebuild keeps 24 modules in flight where that width says 14.
     */
    static int etaConcurrency(int maxModuleConcurrency) {
        return Math.max(1, TestWorkers.jobsBudget(maxModuleConcurrency));
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
                    ordered.add(c.toWorkCost());
                }
            }
        }
        // Any leftover (shouldn't happen) — append in original cost order.
        for (EffortWeights.ModuleCost c : byDir.values()) {
            ordered.add(c.toWorkCost());
        }
        return ordered;
    }

    static List<EffortWeights.ModuleCost> etaCostsFromExplainPlan(
            ExplainPlan plan,
            Path cache,
            int workers,
            @Nullable Path jdksDir,
            @Nullable String profile,
            boolean skipTests,
            boolean verbose,
            int maxModuleConcurrency) {
        Set<Path> projectModules = new HashSet<>();
        for (TaskForecast.Module m : plan.modules()) projectModules.add(m.dir());
        List<String> projectDirs = projectModules.stream().map(Path::toString).toList();
        boolean distrust = SessionContext.current().config().forceOr(false)
                || SessionContext.current().config().rebuildOr(false);
        BuildMetrics metrics = BuildMetrics.load(BuildMetrics.defaultFile());
        StepTimings timings = StepTimings.load(cache);
        // The runner share a module will really get, not the whole machine: `jk build` divides the
        // jobs budget by how wide the build can get (WorkspaceResourcePhase.resolveAutoWorkers), so
        // this asks the same question of the same population through the same function, or the two
        // price different builds — see competesForMachine and BuildGraph.maxReadyWidth for the two
        // ways this disagreed with the executor, both of which under-stated each suite's share.
        Set<Path> dirtyDirs = new LinkedHashSet<>();
        for (TaskForecast.Module m : plan.modules()) {
            if (distrust || competesForMachine(m)) dirtyDirs.add(m.dir());
        }
        // Width is the widest simultaneously-ready wave, NOT the number of dirty modules — the same
        // question BuildGraph.maxReadyWidth answers for the executor, asked the same way. Counting
        // dirty modules instead over-states it whenever they are a dependency chain, which is the
        // common incremental case, and under-states every suite's runner share by the same factor.
        int dirtyWidth = BuildGraph.maxReadyWidth(dirtyDirs, plan.edges());
        if (maxModuleConcurrency > 0) dirtyWidth = Math.min(Math.max(1, dirtyWidth), maxModuleConcurrency);
        int jobsBudget = TestWorkers.jobsBudget(maxModuleConcurrency);
        // The live countdown arrives with the share already resolved (workers > 0); explain
        // resolves it here, through the executor's own function.
        int share = workers > 0 ? workers : TestWorkers.autoShare(jobsBudget, dirtyWidth);
        Perf.note(
                "eta-width",
                "dirtyModules",
                dirtyDirs.size(),
                "dirtyWidth",
                dirtyWidth,
                "jobs",
                jobsBudget,
                "share",
                share);
        List<EffortWeights.ModuleCost> costs = new ArrayList<>();
        for (TaskForecast.Module m : plan.modules()) {
            if (!distrust && !m.dirty()) continue;
            Path mdir = m.dir();
            Set<Path> prereqs = plan.edges().getOrDefault(mdir, Set.of());
            // Local *compile* content only — resource drift must not unlock suite walls.
            boolean localCompile = hasLocalCompileContent(m);
            boolean resourceDrift = hasResourceDriftWork(m);
            boolean testResourceDrift = hasTestResourceDriftWork(m);
            // Native/assembly in the forecast keeps run-tests full (cli ← engine test-dep) even
            // when native itself is cascade-discounted below.
            // Full suite price only when this module owns evidence of change: its own compile
            // content, a heavy packaging tail, or other material work that is not the suite.
            // A suite-only dirty step is a drifted run-tests stamp key, not work — price a
            // recheck. Cascade-forced steps ("dependency changed", "main changed", "compile
            // changed") are a sibling's consequence, not evidence here. The exception is a
            // suite whose last run under these inputs was red: the forecaster marks it, and the
            // live run never skips it.
            boolean otherMaterialWork = m.steps().stream()
                    .anyMatch(s -> (distrust || !s.cached())
                            && !TaskNames.RUN_TESTS.equals(s.name())
                            && !TaskForecast.Module.isBookkeepingStep(s.name())
                            && !isCascadeForcedStep(s));
            boolean redRerun = m.steps().stream().anyMatch(BuildEta::isRedRerun);
            boolean keepFullTests = localCompile || hasHeavyPackagingTail(m) || otherMaterialWork || redRerun;
            List<String> running = new ArrayList<>();
            int cascadeRecheck = 0;
            for (TaskForecast.Task s : m.steps()) {
                if (!distrust && s.cached()) continue;
                // Price material work only — bookkeeping steps (parse-build, stamps, …) are not
                // cache hits but must not inflate ETA toward a full monorepo wall.
                if (!distrust && TaskForecast.Module.isBookkeepingStep(s.name())) continue;
                if (!distrust
                        && shouldDiscountCascadeStep(
                                s, localCompile, resourceDrift, keepFullTests, testResourceDrift)) {
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
                PlannerTails.appendDeclaredTails(builder, inputs);
                for (Task s : builder.build().steps()) running.add(s.name());
            }
            if (running.isEmpty()) {
                // Resource-only producer or pure cascade recheck — milliseconds, not suite walls.
                int w = Math.max(EffortWeights.TOKEN, cascadeRecheck + (m.dirty() ? 1 : 0));
                costs.add(EffortWeights.costOf(mdir, prereqs, w, 0));
                continue;
            }
            Map<String, Integer> counts = new HashMap<>();
            if (m.testCount() > 0) counts.put(TaskNames.RUN_TESTS, m.testCount());
            if (m.sourceCount() > 0) {
                counts.put(TaskNames.COMPILE_JAVA, m.sourceCount());
                counts.put(TaskNames.COMPILE_TEST, m.sourceCount());
            }
            int classGuess = m.testCount() > 0 ? Math.max(1, m.testCount() / 3) : 0;
            // The share is a cap, not a demand: a suite with fewer classes than runners gets fewer.
            int testW = TestWorkers.resolve(share, classGuess, share);
            EffortWeights.ModuleCost priced = EffortWeights.costFromRunningSteps(
                    mdir, prereqs, running, metrics, timings, projectDirs, counts, testW);
            // This module's own `.jk/` scripts, for the anchors this build will actually reach.
            // They are prefix work — before-compile/after-compile/before-package all land ahead of
            // the suite-vs-tail split — so they go on `weight` and not on either branch.
            long logicMs = BuildLogicEffort.moduleMillis(mdir, m, metrics);
            if (logicMs > 0) {
                priced = priced.withWeight(priced.weight() + EffortWeights.flatWeight(logicMs));
            }
            if (cascadeRecheck > 0) {
                // withWeight, not costOf: the four-argument form zeroes tailWeight, which reprices
                // the module as the sum of its steps instead of its longer branch.
                priced = priced.withWeight(priced.weight() + cascadeRecheck);
            }
            costs.add(priced);
        }
        return costs;
    }

    /**
     * Steps that should not contribute full historical walls to open-loop ETA. Cascade-forced
     * compile/package/native and resource-only producers almost always action-cache hit for
     * compile/test.
     */
    static boolean shouldDiscountCascadeStep(
            TaskForecast.Task s,
            boolean localCompile,
            boolean resourceDrift,
            boolean keepFullTests,
            boolean testResourceDrift) {
        if (s == null || s.cached()) return false;
        String name = s.name();
        // TEST-resource drift reruns the suite for real — test action keys hash test resources —
        // so run-tests must keep its full wall no matter which rule below would discount it.
        if (TaskNames.RUN_TESTS.equals(name) && testResourceDrift) {
            return false;
        }
        // Cascade-forced compile/package without local source edits.
        if (!localCompile && isCascadeForcedStep(s) && isCompileOrPackageStep(name)) {
            return true;
        }
        // Cascade-forced native ("rebuild · compile changed") without local compile — cli native
        // often SKIPPED while tests still run (dogfood: priced ~34s native, actual SKIPPED).
        if (!localCompile && isCascadeForcedStep(s) && TaskNames.NATIVE_IMAGE.equals(name)) {
            return true;
        }
        // MAIN-resource drift schedules copy/package only — never a full compile/test suite
        // (dogfood-validated discount; the test-resource case exited above).
        if (!localCompile && resourceDrift && (isCompileStepName(name) || TaskNames.RUN_TESTS.equals(name))) {
            return true;
        }
        // Pure cascade module: discount tests. Cli keeps tests when a heavy tail is forecast
        // (test-dep on a dirty engine) even if native itself is discounted.
        if (!localCompile && !keepFullTests && TaskNames.RUN_TESTS.equals(name)) {
            return true;
        }
        return false;
    }

    /**
     * "Exactly zero sources changed" — the count must not be a suffix of a larger number
     * ("10 sources changed"), see . Text form from {@code JavaCompile}:
     * {@code "1 source changed"} / {@code "<n> sources changed"}.
     */
    private static final Pattern ZERO_SOURCES = Pattern.compile("(?<!\\d)0 sources? changed");

    /**
     * True when the module has real local compile content (sources/options/classpath) — not
     * resource drift alone, and not a zero-source partial.
     */
    static boolean hasLocalCompileContent(TaskForecast.Module m) {
        if (m == null || m.steps() == null) return false;
        for (TaskForecast.Task s : m.steps()) {
            if (s.cached() || !isCompileStepName(s.name())) continue;
            String t = s.text() == null ? "" : s.text();
            // "compile · 0 sources changed" is not material work. Digit-guarded: a bare
            // contains("0 source") also matched "10/20/…N0 sources changed" and silently
            // discounted whole test suites for modules with a multiple-of-ten edit.
            if (ZERO_SOURCES.matcher(t).find()) continue;
            if (s.status() == TaskForecast.Status.PARTIAL || s.status() == TaskForecast.Status.FULL) {
                return true;
            }
            if (t.contains("source changed")
                    || t.contains("sources")
                    || t.contains("no incremental")
                    || t.contains("classpath")
                    || t.contains("options")
                    || t.contains("not locked")
                    || t.contains(ManifestPaths.MANIFEST)) {
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
            if (TaskNames.COPY_RESOURCES.equals(s.name()) || TaskNames.COPY_TEST_RESOURCES.equals(s.name()))
                return true;
            String t = s.text() == null ? "" : s.text();
            if (TaskNames.PACKAGE_JAR.equals(s.name()) && t.contains("resources changed")) return true;
        }
        return false;
    }

    /**
     * TEST-resource drift specifically — the suite genuinely reruns (test action keys hash test
     * resources), so unlike main-resource drift it must never discount {@code run-tests}.
     */
    static boolean hasTestResourceDriftWork(TaskForecast.Module m) {
        if (m == null || m.steps() == null) return false;
        for (TaskForecast.Task s : m.steps()) {
            if (s.cached()) continue;
            if (TaskNames.COPY_TEST_RESOURCES.equals(s.name())) return true;
        }
        return false;
    }

    /** Native / assembly / OCI tails — signal to keep full run-tests (cli-shaped test-dep). */
    static boolean hasHeavyPackagingTail(TaskForecast.Module m) {
        if (m == null || m.steps() == null) return false;
        return m.steps().stream()
                .anyMatch(s -> !s.cached()
                        && (TaskNames.NATIVE_IMAGE.equals(s.name())
                                || TaskNames.WRITE_IMAGE.equals(s.name())
                                || TaskNames.PACKAGE_ASSEMBLY.equals(s.name())));
    }

    /** A {@code run-tests} step the forecaster marked as a re-run after a red suite. */
    static boolean isRedRerun(TaskForecast.Task s) {
        if (s == null || s.cached() || !TaskNames.RUN_TESTS.equals(s.name())) return false;
        String t = s.text() == null ? "" : s.text();
        return t.contains(TaskForecast.LAST_RUN_FAILED);
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
        return name.startsWith(TaskNames.COMPILE_MAIN)
                || name.startsWith(TaskNames.COMPILE_JAVA)
                || name.startsWith(TaskNames.COMPILE_KOTLIN)
                || name.startsWith(TaskNames.COMPILE_GROOVY)
                || name.startsWith(TaskNames.COMPILE_TEST);
    }

    static boolean isCompileOrPackageStep(String name) {
        if (name == null) return false;
        return isCompileStepName(name) || TaskNames.PACKAGE_JAR.equals(name) || TaskNames.PACKAGE_ASSEMBLY.equals(name);
    }

    /** The seed plus the pre-bias schedule the observation loop compares actual walls against. */
    record Seed(long etaMs, long rawScheduleMs) {}

    /**
     * Single schedule-aware ETA (ms) used by both {@code jk explain} and {@code jk build}'s initial
     * countdown. Costs are already Σ of dirty-step weights (measured step walls preferred). Schedule
     * composes them with concurrency / serial-test bounds. Whole-build history is only a cold seed
     * when the schedule has no costs — never a substitute for step composition.
     */
    private static Seed seedEta(
            Path entryDir,
            List<EffortWeights.ModuleCost> costs,
            Set<Path> dirs,
            int concurrency,
            boolean serial,
            boolean parallelTests,
            Path cache,
            @Nullable Path jdksDir,
            BuildService.HistoryShape shape) {
        BuildService.HistoryShape hist = shape == null ? historyShape() : shape;
        if (costs == null || costs.isEmpty()) {
            // No material dirty work in the forecast — ETA is 0 (cache verify only).
            // NEVER fall back to whole-build history here: that produced a phantom multi-minute
            // seed whenever every step was CACHED / bookkeeping-only, while `jk explain` hid the
            // lie behind "Fully Cached / <1s". History floors apply only when there is real work.
            return new Seed(0, 0);
        }
        // Always convert at MS_PER_WEIGHT. Costs are priced either as absolute step walls
        // (flatWeight(avgMs) round-trips with ×150) or residual/static units trained in that same
        // reference frame.
        HistoryMatch okMatch = okHistoryMatch(entryDir, hist);
        BuildMetrics.Stats okHist = okMatch.stats();
        // Whole-build history floor only for true full rebuilds — not merely "many modules are
        // dirty." Require substantial scheduled weight breadth, or an explicit --force/--rebuild.
        boolean fullWork = isFullWorkShape(hist, costs);
        Perf.note(
                "eta-seed",
                "fullWork",
                fullWork,
                "okHist",
                okHist == null ? "none" : okHist.count() + "x avg=" + okHist.avgMillis());
        boolean coldFull = fullWork && (okHist == null || okHist.count() == 0);
        int etaConcurrency = concurrency;
        if (coldFull && !serial && concurrency > 1) {
            // ~75% of jobs: monorepo rebuilds rarely sustain full -j throughput.
            etaConcurrency = Math.max(1, (int) Math.ceil(concurrency * 0.75));
        }
        long base =
                EffortWeights.scheduleMillis(costs, etaConcurrency, serial, parallelTests, EffortWeights.MS_PER_WEIGHT);
        // The root's build logic. An `after-build` script runs once, after every module, so it
        // belongs on the schedule as a serial tail rather than inside any module's wall. The
        // forecast classifies build-logic steps as bookkeeping and never lists them, so this adds
        // the measured script cost the schedule would otherwise omit.
        base += rootBuildLogicMillis(entryDir);
        long rawSchedule = base;
        // Learned schedule-contention bias (actual/simulated EWMA from real runs, keyed by build
        // shape): the ideal schedule composes measured step walls with perfect overlap; real
        // workspaces pay JVM spawn queuing, PluginSlots gating, and IO contention the model cannot
        // see.
        //
        // The gate is inside the biased amount on purpose. It is not a constant that deserves to
        // sit outside: the same gate measures 4.6 s on a two-module build and 12.3 s on a wider
        // one, because it is a JVM walking the tree while test JVMs compete for the same machine.
        // It contends like everything else here, so it scales like everything else here.
        if (costs.size() >= ScheduleBias.MIN_MODULES) {
            base = Math.round(base * ScheduleBias.current(entryDir, costs.size()));
        }
        if (coldFull && base > 0) {
            base = Math.round(base * 1.08);
        }
        if (fullWork) {
            BuildMetrics.Stats plainFull =
                    okHistory(entryDir, new BuildService.HistoryShape(false, hist.dirtyModules()));
            BuildMetrics.Stats floorSrc = higherAvg(okHist, plainFull);
            // Credibility: the shape lookup can fall back to bare project stats, whose average
            // is dominated by sub-second cached no-ops (observed: 4x avg=262ms posing as
            // full-rebuild history). A floor that cannot even be one real build is no floor.
            if (floorSrc != null && floorSrc.count() > 0 && floorSrc.maxMillis() >= 5_000L) {
                long floor = floorSrc.avgMillis();
                if (floorSrc.count() >= 2 && floorSrc.maxMillis() > floor) {
                    floor = hist.rebuild()
                            ? Math.round(0.25 * floorSrc.avgMillis() + 0.75 * floorSrc.maxMillis())
                            : Math.round(0.5 * floorSrc.avgMillis() + 0.5 * floorSrc.maxMillis());
                }
                // The floor catches sims that under-price unlearned steps — it must not let
                // stale history override a structurally faster schedule outright (phase-gated
                // pipelining halved real walls while history still remembered the serialized
                // ones). Cap its uplift at 1.5x the simulated schedule; as post-change
                // builds land, avg/max converge and the cap stops binding.
                floor = Math.min(floor, Math.round(base * 1.5));
                Perf.note(
                        "eta-seed",
                        "base",
                        base + "ms",
                        "floor",
                        floor + "ms",
                        "histCount",
                        floorSrc.count(),
                        "histAvg",
                        floorSrc.avgMillis(),
                        "histMax",
                        floorSrc.maxMillis(),
                        "coldFull",
                        coldFull);
                if (floor > base) base = floor;
            }
        }
        // One-sided clamp for absurd over-estimates only (never pull incremental work up to history).
        // Then a tiny open-loop preference for mild over-estimate (finishing early feels worse than late).
        return new Seed(preferSlightOverEstimate(applyHistoryPrior(base, okMatch)), rawSchedule);
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
    static boolean isFullWorkShape(BuildService.HistoryShape hist, List<EffortWeights.ModuleCost> costs) {
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
        boolean verbose = EnvValues.bool(System::getenv, "JK_ETA_SEED_LOG").orElse(false) || Perf.enabled();
        // Always note serious misses so they show up in engine logs without env.
        boolean serious = relErr >= 0.35 && actualExecuteMs >= 5_000L;
        if (!verbose && !serious) return;
        Log.info(String.format(
                "jk: eta-seed quality R0=%dms actual=%dms ratio=%.2f relErr=%.0f%% dirty=%d",
                seedMs, actualExecuteMs, ratio, relErr * 100.0, dirtyModules));
    }

    /** History key with known dirty-module count so explain and build share the same prior tier. */
    private static BuildService.HistoryShape historyShapeForCosts(int dirtyModuleCount) {
        boolean rebuild = SessionContext.current().config().rebuildOr(false)
                || SessionContext.current().config().forceOr(false);
        return new BuildService.HistoryShape(rebuild, Math.max(0, dirtyModuleCount));
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
    static long applyHistoryPrior(long base, BuildMetrics.@Nullable Stats okHist) {
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
     * As {@link #applyHistoryPrior(long, BuildMetrics.Stats)}, but declining to clamp when the
     * samples did not come from this build's own kind.
     *
     * <p>Takes the {@link HistoryMatch} rather than a boolean on purpose: a bare flag in that
     * position is how a caller passing {@code false} silently changes behaviour.
     *
     * <p>A fallback sample is a fine prior for a cold estimate and a terrible bound for a warm one.
     * Clamping across kinds capped a full-rebuild schedule accurate to 2.8% (75.6 s simulated,
     * 73.6 s actual) down to 27.6 s, because a day of incremental builds had left a 13.8 s maximum
     * in the bare-project bucket. The under-read read as a modelling error for a long time, because
     * a clamp leaves no trace in the output.
     */
    static long applyHistoryPrior(long base, @Nullable HistoryMatch match) {
        if (match == null) return base;
        if (!match.sameKind()) {
            BuildMetrics.Stats st = match.stats();
            // Cold seed still deserves a prior; a warm schedule does not deserve a foreign bound.
            return base == 0 && st != null && st.count() > 0 ? st.avgMillis() : base;
        }
        return applyHistoryPrior(base, match.stats());
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
    static BuildService.HistoryShape historyShape() {
        var cfg = SessionContext.current().config();
        boolean rebuild = cfg.rebuildOr(false) || cfg.forceOr(false);
        return new BuildService.HistoryShape(rebuild, -1);
    }

    static BuildMetrics.Stats okHistory(Path entryDir, BuildService.HistoryShape shape) {
        return okHistoryMatch(entryDir, shape).stats();
    }

    /**
     * History samples plus whether they came from this build's own kind.
     *
     * <p>{@code kind} is {@code build} or {@code build:rebuild} so full-rebuild walls do not pollute
     * incremental estimates and vice versa. The fallback chain walks off that kind (bare project
     * dir, then host) when the shaped bucket is thin; {@code sameKind} lets a caller decline to
     * treat a fallback as a bound. Fallbacks are still returned — a reasonable prior for a cold
     * project — but they must not clamp a full-rebuild schedule against incremental history.
     */
    record HistoryMatch(BuildMetrics.Stats stats, boolean sameKind) {
        static final HistoryMatch NONE = new HistoryMatch(BuildMetrics.Stats.EMPTY, false);
    }

    static HistoryMatch okHistoryMatch(Path entryDir, BuildService.HistoryShape shape) {
        BuildMetrics metrics = BuildMetrics.load(BuildMetrics.defaultFile());
        BuildService.HistoryShape s = shape == null ? new BuildService.HistoryShape(false, -1) : shape;
        String kind = s.kind();
        if (entryDir != null) {
            String shaped = s.dirKey(entryDir);
            var exact = metrics.invocation(kind, shaped).map(BuildMetrics.Entry::ok);
            if (exact.isPresent() && exact.get().count() > 0) return new HistoryMatch(exact.get(), true);
            // Same path, any dirty-count for this kind — the write side always shapes the
            // key (path#dN), so merge across shapes instead of an exact bare lookup that
            // reads a never-written key. Still this kind, so still a match.
            BuildMetrics.Stats shapes = metrics.okAcrossShapes(kind, BuildMetrics.slashKey(entryDir.toString()));
            if (shapes.count() > 0) return new HistoryMatch(shapes, true);
            // Fall back to plain "build" for the path when the shaped kind has no samples. This
            // one crosses kinds: `build` walls are incremental, and this branch is only reached
            // by `build:rebuild`.
            if (!"build".equals(kind)) {
                var crossKind = metrics.invocation("build", BuildMetrics.slashKey(entryDir.toString()))
                        .map(BuildMetrics.Entry::ok);
                if (crossKind.isPresent() && crossKind.get().count() > 0) {
                    return new HistoryMatch(crossKind.get(), false);
                }
            }
        }
        var hostShaped = metrics.invocation(kind, "").map(BuildMetrics.Entry::ok);
        // Host-level samples are this kind but another project's tree — a prior, not a bound.
        if (hostShaped.isPresent() && hostShaped.get().count() > 0) {
            return new HistoryMatch(hostShaped.get(), false);
        }
        return metrics.invocation("build", "")
                .map(BuildMetrics.Entry::ok)
                .map(st -> new HistoryMatch(st, false))
                .orElse(HistoryMatch.NONE);
    }
}
