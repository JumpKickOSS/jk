// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.HeapPlan;
import cc.jumpkick.run.BuildPlan;
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

    public static BuildService.EtaModel estimateEtaModel(
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
            return new BuildService.EtaModel(seed.etaMs(), costs, concurrency, serialEta, seed.rawScheduleMs());
        } catch (RuntimeException e) {
            // Never fail explain/build over the estimate — but do not silently advertise 0s/empty.
            System.err.println("jk: ETA estimate failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return BuildService.EtaModel.empty();
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
            boolean testResourceDrift = hasTestResourceDriftWork(m);
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
            TaskForecast.Task s,
            boolean localCompile,
            boolean resourceDrift,
            boolean keepFullTests,
            boolean testResourceDrift) {
        if (s == null || s.cached()) return false;
        String name = s.name();
        // TEST-resource drift reruns the suite for real — test action keys hash test resources —
        // so run-tests must keep its full wall no matter which rule below would discount it.
        if ("run-tests".equals(name) && testResourceDrift) {
            return false;
        }
        // Cascade-forced compile/package without local source edits.
        if (!localCompile && isCascadeForcedStep(s) && isCompileOrPackageStep(name)) {
            return true;
        }
        // Cascade-forced native ("rebuild · compile changed") without local compile — cli native
        // often SKIPPED while tests still run (dogfood: priced ~34s native, actual SKIPPED).
        if (!localCompile && isCascadeForcedStep(s) && "native-image".equals(name)) {
            return true;
        }
        // MAIN-resource drift schedules copy/package only — never a full compile/test suite
        // (dogfood-validated discount; the test-resource case exited above).
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
     * "Exactly zero sources changed" — the count must not be a suffix of a larger number
     * ("10 sources changed"), see . Text form from {@code JavaCompile}:
     * {@code "1 source changed"} / {@code "<n> sources changed"}.
     */
    private static final java.util.regex.Pattern ZERO_SOURCES =
            java.util.regex.Pattern.compile("(?<!\\d)0 sources? changed");

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

    /**
     * TEST-resource drift specifically — the suite genuinely reruns (test action keys hash test
     * resources), so unlike main-resource drift it must never discount {@code run-tests}.
     */
    static boolean hasTestResourceDriftWork(TaskForecast.Module m) {
        if (m == null || m.steps() == null) return false;
        for (TaskForecast.Task s : m.steps()) {
            if (s.cached()) continue;
            if ("copy-test-resources".equals(s.name())) return true;
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

    /**
     * Single schedule-aware ETA (ms) used by both {@code jk explain} and {@code jk build}'s initial
     * countdown. Costs are already Σ of dirty-step weights (measured step walls preferred). Schedule
     * composes them with concurrency / serial-test bounds. Whole-build history is only a cold seed
     * when the schedule has no costs — never a substitute for step composition.
     */
    /** The seed plus the pre-bias schedule the observation loop compares actual walls against. */
    record Seed(long etaMs, long rawScheduleMs) {}

    private static Seed seedEta(
            Path entryDir,
            List<EffortWeights.ModuleCost> costs,
            Set<Path> dirs,
            int concurrency,
            boolean serial,
            boolean parallelTests,
            Path cache,
            Path jdksDir,
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
        BuildMetrics.Stats okHist = okHistory(entryDir, hist);
        // Whole-build history floor only for true full rebuilds — not merely "many modules are
        // dirty." Require substantial scheduled weight breadth, or an explicit --force/--rebuild.
        boolean fullWork = isFullWorkShape(hist, costs);
        if (Perf.ENABLED) {
            System.err.println("[jk-perf] eta-seed fullWork=" + fullWork + " okHist="
                    + (okHist == null ? "none" : okHist.count() + "x avg=" + okHist.avgMillis()));
        }
        boolean coldFull = fullWork && (okHist == null || okHist.count() == 0);
        int etaConcurrency = concurrency;
        if (coldFull && !serial && concurrency > 1) {
            // ~75% of jobs: monorepo rebuilds rarely sustain full -j throughput.
            etaConcurrency = Math.max(1, (int) Math.ceil(concurrency * 0.75));
        }
        long base =
                EffortWeights.scheduleMillis(costs, etaConcurrency, serial, parallelTests, EffortWeights.MS_PER_WEIGHT);
        long rawSchedule = base;
        // Learned schedule-contention bias (actual/simulated EWMA from real runs): the ideal
        // schedule composes measured step walls with perfect overlap; real workspaces pay JVM
        // spawn queuing, PluginSlots gating, and IO contention the model cannot see. Applied
        // only to multi-module schedules — the observation loop only learns from those.
        if (costs.size() >= ScheduleBias.MIN_MODULES) {
            base = Math.round(base * ScheduleBias.current(entryDir));
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
                // ones, JK-2216). Cap its uplift at 1.5x the simulated schedule; as post-change
                // builds land, avg/max converge and the cap stops binding.
                floor = Math.min(floor, Math.round(base * 1.5));
                if (Perf.ENABLED) {
                    System.err.println("[jk-perf] eta-seed base=" + base + "ms floor=" + floor + "ms hist(count="
                            + floorSrc.count() + " avg=" + floorSrc.avgMillis() + " max=" + floorSrc.maxMillis()
                            + ") coldFull=" + coldFull);
                }
                if (floor > base) base = floor;
            }
        }
        // One-sided clamp for absurd over-estimates only (never pull incremental work up to history).
        // Then a tiny open-loop preference for mild over-estimate (finishing early feels worse than late).
        return new Seed(preferSlightOverEstimate(applyHistoryPrior(base, okHist)), rawSchedule);
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
    static BuildService.HistoryShape historyShape() {
        var cfg = SessionContext.current().config();
        boolean rebuild = cfg.rebuildOr(false) || cfg.forceOr(false);
        return new BuildService.HistoryShape(rebuild, -1);
    }

    static BuildMetrics.Stats okHistory(Path entryDir, BuildService.HistoryShape shape) {
        BuildMetrics metrics = BuildMetrics.load(BuildMetrics.defaultFile());
        BuildService.HistoryShape s = shape == null ? new BuildService.HistoryShape(false, -1) : shape;
        String kind = s.kind();
        if (entryDir != null) {
            String shaped = s.dirKey(entryDir);
            var exact = metrics.invocation(kind, shaped).map(BuildMetrics.Entry::ok);
            if (exact.isPresent() && exact.get().count() > 0) return exact.get();
            // Same path, any dirty-count for this kind — the write side always shapes the
            // key (path#dN), so merge across shapes instead of an exact bare lookup that
            // reads a never-written key.
            BuildMetrics.Stats shapes = metrics.okAcrossShapes(kind, BuildMetrics.slashKey(entryDir.toString()));
            if (shapes.count() > 0) return shapes;
            // Fall back to plain "build" for the path (pre-1156 rows).
            if (!"build".equals(kind)) {
                var legacy = metrics.invocation("build", BuildMetrics.slashKey(entryDir.toString()))
                        .map(BuildMetrics.Entry::ok);
                if (legacy.isPresent() && legacy.get().count() > 0) return legacy.get();
            }
        }
        var hostShaped = metrics.invocation(kind, "").map(BuildMetrics.Entry::ok);
        if (hostShaped.isPresent() && hostShaped.get().count() > 0) return hostShaped.get();
        return metrics.invocation("build", "").map(BuildMetrics.Entry::ok).orElse(BuildMetrics.Stats.EMPTY);
    }
}
