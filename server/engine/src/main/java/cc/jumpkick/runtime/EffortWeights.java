// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.builds.AggregatedMetrics;
import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.FetchTimings;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.jdk.JdkLts;
import cc.jumpkick.jdk.JdkRegistry;
import cc.jumpkick.jdk.JdkResolution;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ModuleLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.ContextPropagator;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.FreshnessStamp;
import cc.jumpkick.test.TestWorkers;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Predicts each build step's progress-bar weight from on-disk state at plan start. Skip
 * detection uses {@link FreshnessStamp#looksFresh}; compile→test→package are correlated (if compile
 * will run, consumers are reserved too). Fresh/cached steps reserve {@link #TOKEN} (not zero) so
 * the aggregate bar keeps a denominator. Mispredictions only mis-size a slice — closed
 * by step-end auto-fill. See {@code docs/perf/progress-contract.md}.
 */
public final class EffortWeights {

    private EffortWeights() {}

    /**
     * When set, jar-derived tails (native / assembly / OCI) over-reserve full learned walls even if
     * outputs look mtime-fresh vs the pre-build jar. Dirty-module prepare always sets this —
     * upstream-dirty recompile rewrites the jar without touching local source stamps, which is
     * exactly when a naïve freshness check under-counted native-image to weight 0.
     */
    private static final ThreadLocal<Boolean> OVER_RESERVE_TAILS = new ThreadLocal<>();

    static {
        // BuildPlan.estimatedTotalWeight()/run() evaluate weight suppliers on JkThreads pool
        // workers; the flag must ride that hop like the session context does. Capture
        // happens on the submitting thread (inside withOverReserveTails), restore on the worker;
        // remove() in finally keeps shared cpu() workers clean.
        // SessionContext's static init uses bind() (displaces); force it to land before our add().
        SessionContext.current();
        ContextPropagator.add(new ContextPropagator.Propagator() {
            @Override
            public Runnable wrapRunnable(Runnable r) {
                if (!overReserveTails()) return r;
                return () -> {
                    OVER_RESERVE_TAILS.set(Boolean.TRUE);
                    try {
                        r.run();
                    } finally {
                        OVER_RESERVE_TAILS.remove();
                    }
                };
            }

            @Override
            public <T> Callable<T> wrapCallable(Callable<T> c) {
                if (!overReserveTails()) return c;
                return () -> {
                    OVER_RESERVE_TAILS.set(Boolean.TRUE);
                    try {
                        return c.call();
                    } finally {
                        OVER_RESERVE_TAILS.remove();
                    }
                };
            }
        });
    }

    /** Run {@code body} with jar-derived tails forced to full bar weight (dirty prepare / run). */
    public static <T> T withOverReserveTails(Callable<T> body) {
        OVER_RESERVE_TAILS.set(Boolean.TRUE);
        try {
            return body.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            OVER_RESERVE_TAILS.remove();
        }
    }

    static boolean overReserveTails() {
        return Boolean.TRUE.equals(OVER_RESERVE_TAILS.get());
    }

    /**
     * Plan/runtime weight when a step is known skip/cache-hit but still appears in the plan
     * . Keeps a non-zero phase tick so the aggregate bar has a denominator without
     * inventing full compile/test cost.
     */
    public static final int TOKEN = 1;

    /** Omitted / no contribution. Prefer {@link #TOKEN} when the step still runs a short check. */
    static final int SKIP = 0;

    static final int RESTORE = 3;
    /** Cold static reservation for test JVM fork + framework init. */
    static final int TEST_STARTUP = 15;

    static final int TEST_METHOD = 8;
    /**
     * Learnable fixed floor for run-tests — must stay below typical hot-suite residual so samples
     * are not dropped (unlike the larger cold {@link #TEST_STARTUP}).
     */
    static final int TEST_STARTUP_FLOOR = 2;

    static final int COMPILE_FLOOR = 2;
    static final int ARTIFACT_FETCH = 8;

    /** Weight unit ≈150 ms — {@code BuildPlan} interpolation constant. */
    public static final int MS_PER_WEIGHT = 150;

    static final int PACKAGE_JAR = 5;
    static final int JDK_DOWNLOAD = 70;
    static final int ASSEMBLY_RUN = 10;
    /**
     * Cold native-image reservation when size/metrics unknown. Prefer {@link NativeEffort} which
     * sizes by classpath bytes and calibration host scale; this flat weight is the last-resort
     * floor (~90s at {@link #MS_PER_WEIGHT}).
     */
    static final int NATIVE_RUN = 600;
    /** Cold OCI build floor (~30s). */
    static final int OCI_RUN = 200;

    static final int OCI_SKIP = 2;

    /**
     * Per-step predicted weights for one module. {@code fullyCached} means every work step is
     * {@link #SKIP} — always-run steps should shrink to a token touch.
     */
    public record Plan(
            int sync,
            int compileJava,
            int compileKotlin,
            int compileGroovy,
            int compileTest,
            int runTests,
            int pkg,
            boolean fullyCached) {}

    /** {@code ceil(sources × 0.1)}, floored at 1 once the step runs at all. */
    static int compileWeight(int sources) {
        return Math.max(1, (sources + 9) / 10);
    }

    /** Cold test-step weight: {@link #TEST_STARTUP} plus per-method term. */
    public static int runTestsWeight(int methods) {
        return TEST_STARTUP + Math.max(0, methods) * TEST_METHOD;
    }

    /**
     * Hierarchical test weight MVP): prefer learned module rate × method count; else
     * class-count × derived class rate; else method static floor. Does not replace
     * {@link #learned} — callers compose: {@code learned(..., runTestsHierarchical(...))}.
     */
    public static int runTestsHierarchical(int methods, int classes) {
        int m = Math.max(0, methods);
        int c = Math.max(0, classes);
        if (m > 0) return runTestsWeight(m);
        if (c > 0) return TEST_STARTUP + c * (TEST_METHOD * 3); // ~3 methods/class cold guess
        return TEST_STARTUP;
    }

    /**
     * Phase rollup: sum of flat learned step weights for a module (compile + test + package).
     * Missing steps contribute 0. Used for module-level schedule costs when shape memo is cold.
     */
    public static int phaseRollupWeight(String dir, BuildMetrics metrics) {
        if (metrics == null) metrics = BuildMetrics.load(BuildMetrics.defaultFile());
        int sum = 0;
        for (String step : List.of(
                TaskNames.COMPILE_JAVA,
                TaskNames.COMPILE_KOTLIN,
                TaskNames.COMPILE_GROOVY,
                TaskNames.COMPILE_TEST,
                TaskNames.RUN_TESTS,
                TaskNames.PACKAGE_JAR,
                TaskNames.RESOLVE_DEPS,
                TaskNames.COPY_RESOURCES)) {
            var e = metrics.step(dir == null ? "" : dir, step);
            if (e.isPresent() && e.get().ok().count() >= MIN_METRICS_SAMPLES) {
                sum += flatWeight(e.get().ok().avgMillis());
            }
        }
        return sum;
    }

    /** Learnable startup floor subtracted before recording a per-unit rate. */
    static int floor(String step) {
        return switch (step) {
            case TaskNames.RUN_TESTS -> TEST_STARTUP_FLOOR;
            case TaskNames.COMPILE_JAVA, TaskNames.COMPILE_KOTLIN, TaskNames.COMPILE_GROOVY, TaskNames.COMPILE_TEST ->
                COMPILE_FLOOR;
            default -> 0;
        };
    }

    /** Minimum successful runs before a metrics average outranks a static constant (bar weights). */
    static final int MIN_METRICS_SAMPLES = 3;

    /**
     * Forecast/plan step names → {@link BuildMetrics} / plan step names. Explain uses
     * {@code compile-main}; the live plan and metrics store {@code compile-java}.
     */
    public static String metricsStepName(String step) {
        if (step == null || step.isBlank()) return "";
        return switch (step) {
            case "compile-main" -> TaskNames.COMPILE_JAVA;
            default -> step;
        };
    }

    /**
     * Success-only average wall for one module step (ms), or 0 when never recorded. Count ≥ 1 is
     * enough — ETA composes dirty steps from measured pieces, not whole-build priors.
     */
    public static long stepOkAvgMillis(BuildMetrics metrics, String dir, String step) {
        long own = stepOkAvgMillisOwn(metrics, dir, step);
        return own > 0 ? own : stepOkAvgMillisHost(metrics, step);
    }

    /** Module-own tier of {@link #stepOkAvgMillis} — 0 when this module never ran the step here. */
    static long stepOkAvgMillisOwn(BuildMetrics metrics, String dir, String step) {
        String key = metricsStepName(step);
        if (key.isEmpty()) return 0;
        // Prefer last successful wall (more recent than trimmed mean) when credible.
        long fromAgg = stepWallFromAggregates(dir == null ? "" : dir, key, true);
        if (fromAgg > 0) return fromAgg;
        if (metrics == null) metrics = BuildMetrics.load(BuildMetrics.defaultFile());
        var own = metrics.step(dir == null ? "" : dir, key);
        if (own.isPresent() && own.get().ok().count() >= 1 && own.get().ok().avgMillis() > 0) {
            return own.get().ok().avgMillis();
        }
        return 0;
    }

    /** Host tier of {@link #stepOkAvgMillis}: the cross-module average wall for {@code step}. */
    static long stepOkAvgMillisHost(BuildMetrics metrics, String step) {
        String key = metricsStepName(step);
        if (key.isEmpty()) return 0;
        long fromAgg = stepWallFromAggregates("", key, true);
        if (fromAgg > 0) return fromAgg;
        if (metrics == null) metrics = BuildMetrics.load(BuildMetrics.defaultFile());
        var host = metrics.step("", key);
        if (host.isPresent() && host.get().ok().count() >= 1 && host.get().ok().avgMillis() > 0) {
            return host.get().ok().avgMillis();
        }
        return 0;
    }

    /**
     * Read last/mean step wall from harvested metrics. Prefer <strong>last</strong> when it is not
     * a restore blip relative to the mean (heavy steps). Recency beats multi-sample mean for ETA
     * after the suite has been getting faster.
     */
    static long stepWallFromAggregates(String dir, String step, boolean preferLast) {
        try {
            var agg = BuildMetrics.aggregatesForSession();
            String task = metricsStepName(step);
            if (task.isEmpty()) return 0;
            String key;
            if (dir == null || dir.isBlank()) {
                key = "task." + task + ".wall-ms";
            } else {
                key = "module." + AggregatedMetrics.sanitize(dir) + ".task." + task + ".wall-ms";
            }
            Double mean = agg.meanMap().get(key);
            Double last = agg.lastMap().get(key);
            long floor = heavyWallFloorMs(task);
            if (preferLast && last != null && last > 0 && last >= floor) {
                // Reject last if it is a tiny fraction of mean (cache-restore / mostly-warmed
                // noise). The old `|| last >= 5_000` escape made this rejection dead for heavy
                // steps (their floor is already 5s), so one 6s over-floor outlier replaced a
                // stable 60s native mean and under-reserved the slice ~10×.
                if (mean == null || mean <= 0 || last >= mean * 0.25) {
                    return Math.round(last);
                }
            }
            if (mean != null && mean > 0 && mean >= floor) return Math.round(mean);
            if (last != null && last > 0 && last >= floor) return Math.round(last);
            // Non-heavy steps: no floor
            if (floor == 0) {
                if (preferLast && last != null && last > 0) return Math.round(last);
                if (mean != null && mean > 0) return Math.round(mean);
            }
            return 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Metrics-learned flat weight for fixed-cost steps: module avg → host avg → {@code
     * staticWeight}. Success-only samples.
     */
    static int learnedFixedWeight(String dir, String step, int staticWeight) {
        return learnedFixedWeight(BuildMetrics.load(BuildMetrics.defaultFile()), dir, step, staticWeight);
    }

    static int learnedFixedWeight(BuildMetrics metrics, String dir, String step, int staticWeight) {
        // Heavy IO steps (native-image, OCI) are rare and long — one successful wall is enough
        // to beat the cold floor; waiting for 3 samples left the bar on a 15s token for months.
        int minSamples = heavyFixedStep(step) ? 1 : MIN_METRICS_SAMPLES;
        long floorMs = heavyFixedStep(step) ? heavyWallFloorMs(step) : 0;
        var own = metrics.step(dir, metricsStepName(step));
        if (own.isPresent() && own.get().ok().count() >= minSamples) {
            long avg = own.get().ok().avgMillis();
            // Ignore poisoned cache-restore samples (e.g. native-image "32ms" success).
            if (avg >= floorMs) return flatWeight(avg);
        }
        var host = metrics.step("", metricsStepName(step));
        if (host.isPresent() && host.get().ok().count() >= minSamples) {
            long avg = host.get().ok().avgMillis();
            if (avg >= floorMs) return flatWeight(avg);
        }
        return staticWeight;
    }

    /** Reject absurdly short measured walls for heavy steps (action-cache restore noise). */
    private static long heavyWallFloorMs(String step) {
        String s = metricsStepName(step);
        if (TaskNames.NATIVE_IMAGE.equals(s)) return 5_000L;
        if (TaskNames.WRITE_IMAGE.equals(s)) return 3_000L;
        return 0L;
    }

    private static boolean heavyFixedStep(String step) {
        String s = metricsStepName(step);
        return TaskNames.NATIVE_IMAGE.equals(s)
                || TaskNames.WRITE_IMAGE.equals(s)
                || TaskNames.PACKAGE_ASSEMBLY.equals(s);
    }

    /** A whole-step historical average (ms) as a flat bar weight. */
    public static int flatWeight(long avgMillis) {
        return Math.max(1, (int) Math.round(avgMillis / (double) MS_PER_WEIGHT));
    }

    /** Back-compat overload with no project context — a two-tier fallback (module → host-median). */
    static int learned(StepTimings timings, String dir, String step, int count, int staticWeight) {
        return learned(timings, dir, step, count, staticWeight, List.of());
    }

    /**
     * Learned weight for a step. Preference: <strong>absolute step wall from {@link BuildMetrics}</strong>
     * (module then host) → residual {@link StepTimings} rate × count → host ms/method → static.
     *
     * <p>Absolute step averages compose correctly for ETA: dirty modules sum their dirty steps; a
     * full rebuild is the same sum over every module. Residual rates scale with count but drift
     * when the trained unit count and the forecast count disagree.
     */
    static int learned(
            StepTimings timings, String dir, String step, int count, int staticWeight, Collection<String> projectDirs) {
        return learned(
                timings, BuildMetrics.load(BuildMetrics.defaultFile()), dir, step, count, staticWeight, projectDirs);
    }

    static int learned(
            StepTimings timings,
            BuildMetrics metrics,
            String dir,
            String step,
            int count,
            int staticWeight,
            Collection<String> projectDirs) {
        String key = metricsStepName(step);
        // Prefer this module's own measured whole-step wall (even a single success).
        long ownMs = stepOkAvgMillisOwn(metrics, dir, key);
        if (ownMs > 0) return flatWeight(ownMs);
        // Cold module with a known planned method count: the count-scaled host prior beats the
        // host suite-wall average, which prices a 2000-method suite like the host's ~average
        // suite. The host wall stays the fallback when no count is known.
        if (TaskNames.RUN_TESTS.equals(key) && count > 0) {
            var msPer = timings.hostAvgTestMethodMs();
            if (msPer.isPresent()) {
                return Math.max(1, (int) Math.round(floor(key) + count * msPer.getAsDouble() / (double) MS_PER_WEIGHT));
            }
        }
        long hostMs = stepOkAvgMillisHost(metrics, key);
        if (hostMs > 0) return flatWeight(hostMs);

        double rate;
        var own = timings.perUnit(dir, key);
        if (own.isPresent()) {
            rate = own.getAsDouble();
        } else {
            var project = timings.medianPerUnit(key, projectDirs);
            if (project.isPresent()) {
                rate = project.getAsDouble();
            } else {
                var host = timings.medianPerUnit(key);
                if (host.isEmpty()) {
                    // No rate anywhere (cold ledger, e.g. right after `jk clean`) — fall back to the
                    // surviving metrics history before conceding to the Step-1 static (which already
                    // embeds Calibration priors when produced by coldStaticWeight).
                    return learnedFixedWeight(metrics, dir, key, staticWeight);
                }
                rate = host.getAsDouble();
            }
        }
        return Math.max(1, (int) Math.round(floor(key) + rate * Math.max(0, count)));
    }

    /**
     * Unit counts for cold/residual pricing from a prepared plan's declared ticks. Matches what
     * {@code jk explain} gets from the forecast ({@code sourceCount}/{@code testCount}): compile
     * steps expose source counts as ticks; {@code run-tests} exposes the method estimate. Used by
     * the build countdown so it shares {@link #costFromRunningSteps} with explain rather than
     * re-pricing with empty counts (which collapses cold test ETA to suite-startup only).
     */
    public static Map<String, Integer> stepCountsFromBuildPlan(BuildPlan plan) {
        Map<String, Integer> counts = new HashMap<>();
        if (plan == null) return counts;
        for (Task s : plan.steps()) {
            String key = metricsStepName(s.name());
            if (key.isEmpty()) continue;
            int ticks;
            try {
                ticks = s.estimateTicks();
            } catch (RuntimeException e) {
                continue;
            }
            if (ticks > 0) counts.put(key, ticks);
        }
        return counts;
    }

    /**
     * Steps that will do real work in a prepared plan (weight &gt; {@link #TOKEN}). Cached/skip
     * checks stay as tokens and are omitted — same idea as forecast {@code !step.cached}.
     */
    public static List<String> runningStepsFromBuildPlan(BuildPlan plan) {
        List<String> running = new ArrayList<>();
        if (plan == null) return running;
        for (Task s : plan.steps()) {
            try {
                if (s.estimateWeight() > TOKEN) running.add(s.name());
            } catch (RuntimeException e) {
                running.add(s.name());
            }
        }
        return running;
    }

    /**
     * Module cost from the steps that will actually run (forecast non-cached / rebuild-all). Each
     * step is priced from {@link BuildMetrics} ok averages when available so ETA is Σ dirty step
     * walls — not a whole-build {@code build}/{@code build:rebuild} prior.
     *
     * @param stepCounts optional unit counts for cold residual fallback (e.g. {@code run-tests} →
     * method count); may be empty
     */
    public static ModuleCost costFromRunningSteps(
            Path dir,
            Set<Path> prereqs,
            Collection<String> runningSteps,
            BuildMetrics metrics,
            StepTimings timings,
            Collection<String> projectDirs,
            Map<String, Integer> stepCounts) {
        return costFromRunningSteps(dir, prereqs, runningSteps, metrics, timings, projectDirs, stepCounts, 1);
    }

    /**
     * @param testWorkers within-module test JVM count for cold {@code run-tests} walls (1 = serial
     * methods; Mill-shaped {@code -w})
     */
    public static ModuleCost costFromRunningSteps(
            Path dir,
            Set<Path> prereqs,
            Collection<String> runningSteps,
            BuildMetrics metrics,
            StepTimings timings,
            Collection<String> projectDirs,
            Map<String, Integer> stepCounts,
            int testWorkers) {
        if (runningSteps == null || runningSteps.isEmpty()) {
            return new ModuleCost(dir, prereqs, 0, 0);
        }
        if (metrics == null) metrics = BuildMetrics.load(BuildMetrics.defaultFile());
        if (projectDirs == null) projectDirs = List.of();
        if (stepCounts == null) stepCounts = Map.of();
        int weight = 0;
        int testWeight = 0;
        String mod = dir == null ? "" : BuildMetrics.slashKey(dir.toString());
        int wWorkers = Math.max(1, testWorkers);
        for (String raw : runningSteps) {
            String step = metricsStepName(raw);
            if (step.isEmpty()) continue;
            int w;
            if (TaskNames.RUN_TESTS.equals(step)) {
                // Class walls when complete; else method product only if count known (never invent).
                int methods = stepCounts.getOrDefault(step, stepCounts.getOrDefault(raw, 0));
                Map<String, Long> walls = loadClassWalls(mod);
                // classesToRun unknown at plan time → empty; TestEffort falls through to walls-own/method path
                w = TestEffort.weight(mod, walls, List.of(), methods, timings, projectDirs, metrics, wWorkers);
            } else if (TaskNames.NATIVE_IMAGE.equals(step)) {
                w = NativeEffort.weight(dir);
            } else {
                // Prefer this module's own measured whole-task wall; count-scaled/host/static tiers
                // (via learned) only when the module is cold here.
                long ownMs = stepOkAvgMillisOwn(metrics, mod, step);
                if (ownMs > 0) {
                    w = flatWeight(ownMs);
                } else {
                    int defaultCount = 1;
                    int count = stepCounts.getOrDefault(step, stepCounts.getOrDefault(raw, defaultCount));
                    int staticW = coldStaticWeight(step, count, wWorkers);
                    if (staticW <= 0) continue; // unknown tiny task with no history
                    if (timings != null) {
                        w = learned(timings, metrics, mod, step, count, staticW, projectDirs);
                    } else {
                        long hostMs = stepOkAvgMillisHost(metrics, step);
                        w = hostMs > 0 ? flatWeight(hostMs) : staticW;
                    }
                }
            }
            weight += w;
            if (TaskNames.RUN_TESTS.equals(step)) testWeight += w;
        }
        return new ModuleCost(dir, prereqs, weight, testWeight);
    }

    /** Class walls from this process buffer or harvested project metrics (no class-file scan). */
    static Map<String, Long> loadClassWalls(String moduleDir) {
        Map<String, Long> live = TestClassWalls.get(moduleDir);
        if (!live.isEmpty()) return live;
        if (moduleDir == null || moduleDir.isBlank()) return Map.of();
        try {
            var agg = AggregatedMetrics.loadAll(JkDirs.builds());
            String prefix = "module." + AggregatedMetrics.sanitize(moduleDir) + ".test-class.";
            String suffix = ".wall-ms";
            Map<String, Long> out = new LinkedHashMap<>();
            for (var e : agg.meanMap().entrySet()) {
                String k = e.getKey();
                if (!k.startsWith(prefix) || !k.endsWith(suffix)) continue;
                String fqcn = k.substring(prefix.length(), k.length() - suffix.length());
                if (!fqcn.isEmpty() && e.getValue() > 0) out.put(fqcn, Math.round(e.getValue()));
            }
            for (var e : agg.lastMap().entrySet()) {
                String k = e.getKey();
                if (!k.startsWith(prefix) || !k.endsWith(suffix)) continue;
                String fqcn = k.substring(prefix.length(), k.length() - suffix.length());
                if (!fqcn.isEmpty() && e.getValue() > 0) out.putIfAbsent(fqcn, Math.round(e.getValue()));
            }
            return out;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /**
     * Cold reservation when a running step has no metrics and no residual rate. Prefers host
     * {@link Calibration} continuous/probe priors (absolute ms → flatWeight); falls back to tight
     * static floors — never the legacy ~1.2s/method {@link #runTestsWeight} for ETA.
     */
    private static int coldStaticWeight(String step, int count) {
        return coldStaticWeight(step, count, 1);
    }

    private static int coldStaticWeight(String step, int count, int testWorkers) {
        int fromCal = coldFromCalibration(step, count, testWorkers);
        if (fromCal > 0) return fromCal;
        // Uncalibrated host: product baselines with scale=1 (same formula as Calibration.scaleBaseline
        // at identity), and the same cold test-worker cap so ETA does not invent linear -w speedup.
        return switch (step) {
            case TaskNames.COMPILE_JAVA, TaskNames.COMPILE_KOTLIN, TaskNames.COMPILE_GROOVY, TaskNames.COMPILE_TEST ->
                flatWeight(Calibration.scaleBaseline(Calibration.BASELINE_COMPILE_PER_SOURCE_MS, 1.0)
                        * Math.max(1, count));
            case TaskNames.RUN_TESTS -> {
                int w = Calibration.coldTestParallel(testWorkers);
                long method = Calibration.scaleBaseline(Calibration.BASELINE_METHOD_MS, 1.0);
                long startup = Calibration.scaleBaseline(Calibration.BASELINE_SUITE_STARTUP_MS, 1.0);
                long body = (long) Math.max(0, count) * method;
                yield flatWeight(startup + (body + w - 1) / w);
            }
            case TaskNames.PACKAGE_JAR ->
                flatWeight(Calibration.scaleBaseline(Calibration.BASELINE_PACKAGE_JAR_MS, 1.0));
            case TaskNames.PACKAGE_ASSEMBLY -> ASSEMBLY_RUN;
            case TaskNames.NATIVE_IMAGE ->
                flatWeight(Calibration.scaleBaseline(Calibration.BASELINE_NATIVE_IMAGE_MS, 1.0));
            case TaskNames.WRITE_IMAGE -> flatWeight(Calibration.scaleBaseline(Calibration.BASELINE_OCI_IMAGE_MS, 1.0));
            case TaskNames.RESOLVE_DEPS,
                    TaskNames.PARSE_BUILD,
                    TaskNames.ENSURE_JDK,
                    TaskNames.COPY_RESOURCES,
                    "copy-test-resources",
                    TaskNames.WRITE_STAMP,
                    TaskNames.WRITE_STAMP_KOTLIN,
                    TaskNames.WRITE_STAMP_GROOVY,
                    TaskNames.BUILD_LOGIC_AFTER_COMPILE,
                    TaskNames.BUILD_LOGIC_BEFORE_PACKAGE -> TOKEN;
            // Post-jk-clean gate: action keys hit, target/ wiped — live work is CAS restore.
            case "restore-outputs" -> RESTORE;
            default -> 0;
        };
    }

    /**
     * Weight units from {@link Calibration#coldStepWallMs} when the host has probe or continuous
     * learned priors; 0 so callers can fall through to tight static floors.
     */
    private static int coldFromCalibration(String step, int count, int testWorkers) {
        try {
            Calibration cal = Calibration.load();
            if (!cal.hasColdPriors()) return 0;
            long wall = cal.coldStepWallMs(step, count, testWorkers);
            return wall > 0 ? flatWeight(wall) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Cold progress-bar / ETA weight for a step using the same host priors as
     * {@link #costFromRunningSteps} (calibration → tight static). Prefer this over
     * {@link #runTestsWeight} / {@link #compileWeight} for real-work forecasts.
     */
    public static int coldWorkWeight(String step, int count) {
        return coldWorkWeight(step, count, 1);
    }

    public static int coldWorkWeight(String step, int count, int testWorkers) {
        int w = coldStaticWeight(metricsStepName(step), count, testWorkers);
        return w > 0 ? w : TOKEN;
    }

    /** Within-module workers for plan-time test weights (matches runtime {@link TestWorkers}). */
    private static int resolveTestWorkersForPredict(BuildPlanner.Inputs in, int classCount) {
        int requested = in != null ? in.workerCount() : 0;
        int jobs = TestWorkers.effectiveJobs();
        return TestWorkers.resolve(requested, classCount, jobs);
    }

    /**
     * Per-unit residual to teach the ledger; negative when duration ≤ floor (skip/cache hit — do
     * not learn a ~0 rate).
     */
    public static double observedPerUnit(String step, long durationMs, int count) {
        double actualWeight = durationMs / (double) MS_PER_WEIGHT;
        double residual = actualWeight - floor(step);
        if (residual <= 0) return -1;
        return residual / Math.max(1, count);
    }

    /**
     * Predict weights; {@code forceRebuild} reserves full compile/test/package even when stamps
     * look fresh (upstream dirty). Over-reserves only — runtime reweight can shrink, never grow.
     */
    public static Plan predict(
            BuildPlanner.Inputs in,
            Cas cas,
            boolean compact,
            boolean useJava,
            boolean useKotlin,
            boolean useGroovy,
            boolean forceRebuild) {
        boolean rerun = in.session().config().rebuildOr(false) || forceRebuild;
        // Same digest-only staleness predicate the build's freshen uses: a stale digest
        // means parse-lock will run a conservative re-lock, so forecast it.
        boolean lockStale = !rerun && AutoLock.isStale(in.dir(), in.lockFile());
        if (lockStale) rerun = true;

        int sync = predictSync(in, cas);
        // Learned per-unit rates (cold ⇒ empty ⇒ static Task-1 weights). Keyed by
        // module dir, the same key the recorder writes at build end.
        StepTimings timings = StepTimings.load(in.cache());
        String mod = in.dir().toString();
        // Sibling modules of this build, for the project-tier learned fallback (empty ⇒ host-median).
        List<String> projectDirs =
                in.projectModules().stream().map(Path::toString).toList();
        int compileJava = SKIP, compileKotlin = SKIP, compileGroovy = SKIP, compileTest = SKIP, runTests = SKIP;
        int pkg = SKIP;
        List<Path> testSrc = new ArrayList<>();
        boolean hadTestSources = false;
        try {
            JkBuild project = JkBuildParser.parse(in.buildFile());
            BuildLayout layout = BuildLayout.of(in.dir(), project);

            boolean javaRun = false;
            if (useJava) {
                List<Path> src = CompileSupport.collectJavaSources(
                        compact ? in.dir().resolve("src") : in.dir().resolve("src/main/java"));
                javaRun = rerun || !FreshnessStamp.looksFresh(layout.classesDir(), FreshnessStamp.JAVA_STAMP, src);
                compileJava = javaRun
                        ? learned(
                                timings,
                                mod,
                                TaskNames.COMPILE_JAVA,
                                src.size(),
                                coldWorkWeight(TaskNames.COMPILE_JAVA, src.size()),
                                projectDirs)
                        : SKIP;
            }
            boolean ktRun = false;
            if (useKotlin) {
                List<Path> src = CompileSupport.collectKotlinSources(in.dir(), compact);
                ktRun = rerun
                        || !FreshnessStamp.looksFresh(layout.kotlinClassesDir(), FreshnessStamp.KOTLIN_STAMP, src);
                compileKotlin = ktRun
                        ? learned(
                                timings,
                                mod,
                                TaskNames.COMPILE_KOTLIN,
                                src.size(),
                                coldWorkWeight(TaskNames.COMPILE_KOTLIN, src.size()),
                                projectDirs)
                        : SKIP;
            }
            boolean gvRun = false;
            if (useGroovy) {
                // The groovy stamp lives in the merged classes dir — that is where
                // write-stamp-groovy writes it (stamp-only freshness, like Kotlin's).
                List<Path> src = CompileSupport.collectGroovySources(in.dir(), compact);
                gvRun = rerun || !FreshnessStamp.looksFresh(layout.classesDir(), FreshnessStamp.GROOVY_STAMP, src);
                compileGroovy = gvRun
                        ? learned(
                                timings,
                                mod,
                                TaskNames.COMPILE_GROOVY,
                                src.size(),
                                coldWorkWeight(TaskNames.COMPILE_GROOVY, src.size()),
                                projectDirs)
                        : SKIP;
            }
            boolean compileRun = javaRun || ktRun || gvRun;

            // Tests + packaging consume the compiled output: if a compile ran (or
            // --force), they run. The precise test skip is decided at run-tests via
            // the CAS marker (which survives `jk clean`); that step reweights down
            // to TOKEN there.
            try {
                testSrc.addAll(TestSupport.collectAllSuiteTestSources(in.dir(), compact));
            } catch (IOException e) {
                // fall back to default suite only
                testSrc.addAll(CompileSupport.collectJavaSources(
                        compact
                                ? in.dir().resolve("test").resolve("src")
                                : in.dir().resolve("src/test/java")));
                testSrc.addAll(CompileSupport.collectKotlinTestSources(in.dir(), compact));
            }
            hadTestSources = !testSrc.isEmpty();
            boolean testWillRun = hadTestSources && (rerun || compileRun);
            // compile-test is an opaque, batch javac/kotlinc call: the step declares
            // .ticks(1), so the recorder learns its rate against a count of 1 — i.e. the
            // learned value IS the whole-step weight, not a per-file rate. Forecast it
            // with count=1 to match (a flat per-step cost). Multiplying it by the test
            // file count here was the bug that ballooned the estimate (e.g. ×46 → ~40s
            // of pure fiction for a ~1.2s compile). The static cold fallback stays a
            // file-count guess. (compile-java is consistent: its.ticks is the source
            // count, the same count predict multiplies, so it stays per-source.)
            compileTest = testWillRun
                    ? learned(
                            timings,
                            mod,
                            TaskNames.COMPILE_TEST,
                            1,
                            coldWorkWeight(TaskNames.COMPILE_TEST, Math.max(1, testSrc.size())),
                            projectDirs)
                    : SKIP;

            int methods = in.estimatedTestCount();
            int classes = TestSupport.estimateAllSuiteTestClassCount(in.dir(), compact);
            // Cold bar weight uses the same host priors as ETA (not legacy TEST_METHOD×8).
            int testWorkers = resolveTestWorkersForPredict(in, classes);
            int staticTests =
                    coldWorkWeight(TaskNames.RUN_TESTS, methods > 0 ? methods : Math.max(1, classes * 3), testWorkers);
            // prefer method-count × run-tests rate; fall back to class-count ×
            // run-tests-class rate when method annotations are not found.
            if (testWillRun) {
                if (methods > 0) {
                    runTests = learned(timings, mod, TaskNames.RUN_TESTS, methods, staticTests, projectDirs);
                } else if (classes > 0) {
                    runTests = learned(timings, mod, "run-tests-class", classes, staticTests, projectDirs);
                } else {
                    runTests = learned(timings, mod, TaskNames.RUN_TESTS, 1, staticTests, projectDirs);
                }
            } else {
                runTests = SKIP;
            }

            boolean jarFresh = !rerun && !compileRun && Files.isRegularFile(layout.mainJar());
            int staticPkg = coldWorkWeight(TaskNames.PACKAGE_JAR, 1);
            pkg = jarFresh ? SKIP : learnedFixedWeight(mod, TaskNames.PACKAGE_JAR, staticPkg);
        } catch (Exception ignored) {
            // Unparseable project / layout — parse-build will surface the real
            // error; skip-ish weights + auto-fill keep the bar honest meanwhile.
        }
        // Fresh steps still sit in the plan for a stamp check — reserve a token so the
        // workspace bar never calibrates to a pure-zero execute band.
        if (useJava && compileJava == SKIP) compileJava = TOKEN;
        if (useKotlin && compileKotlin == SKIP) compileKotlin = TOKEN;
        if (useGroovy && compileGroovy == SKIP) compileGroovy = TOKEN;
        if (compileTest == SKIP && hadTestSources) compileTest = TOKEN;
        if (runTests == SKIP && hadTestSources) runTests = TOKEN;
        if (pkg == SKIP) pkg = TOKEN;
        if (sync == SKIP) sync = TOKEN;

        // A module whose every work step is only a token is fully cached: always-run tails
        // shrink to a touch rather than full static weight.
        boolean fullyCached = isTokenOrSkip(sync)
                && isTokenOrSkip(compileJava)
                && isTokenOrSkip(compileKotlin)
                && isTokenOrSkip(compileGroovy)
                && isTokenOrSkip(compileTest)
                && isTokenOrSkip(runTests)
                && isTokenOrSkip(pkg);
        return new Plan(sync, compileJava, compileKotlin, compileGroovy, compileTest, runTests, pkg, fullyCached);
    }

    /** True when weight is absent or only a token (no real compile/test/package work). */
    static boolean isTokenOrSkip(int weight) {
        return weight <= TOKEN;
    }

    /**
     * {@code ensure-jdk}: 70 only when a JDK download will actually happen — the same condition
     * {@link JdkEnsure} uses ({@code resolve} finds no usable JDK across the whole order, including
     * the current/PATH tiers, and a spec <em>would install</em>). {@code resolve} is offline; the
     * download it predicts is the network cost. Anything resolvable on disk → 1.
     */
    public static int jdkWeight(Path dir, Path jdksDir) {
        try {
            JkBuild project = JkBuildParser.parse(dir.resolve(ManifestPaths.MANIFEST));
            Path lf = LockPaths.lockFile(dir);
            Lockfile lock = Files.exists(lf) ? LockfileReader.read(lf) : null;
            JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
            var req = new JdkResolution.Request(
                    dir,
                    SessionContext.current().jdkSpec(),
                    System.getenv("JK_JDK"),
                    lock != null ? lock.jdk() : null,
                    project.project() != null ? project.project().jdk() : null,
                    project.project() != null ? project.project().javaRelease() : 0,
                    System::getenv);
            var r = JdkResolution.resolve(req, registry, JdkInventory.current(), JdkLts.OFFLINE_LATEST_LTS);
            return (r.jdk().isEmpty() && r.wouldInstall()) ? JDK_DOWNLOAD : SKIP;
        } catch (Exception e) {
            return SKIP;
        }
    }

    /**
     * Packaging dirtiness cascade (hard product rule):
     *
     * <ul>
     *   <li>If the main <strong>jar</strong> will change this run, <strong>native</strong> is dirty
     *       (when the module builds a native image) — never jar-dirty + native-clean.
     *   <li>If the jar <em>or</em> native output will change, <strong>OCI</strong> is dirty (when
     *       the module builds an image) — never jar/native-dirty + OCI-clean.
     * </ul>
     *
     * Bar weights and forecast must follow this; mtime of an old binary vs a pre-build jar is not
     * an independent skip signal.
     */

    /**
     * Assembly jar present and at least as new as the main jar (and not {@code --force}) → skip.
     * Jar dirty ⇒ assembly dirty (same cascade family as native).
     */
    public static int assemblyWeight(Path dir) {
        if (jarWillChange(dir)) {
            return learnedFixedWeight(dir.toString(), TaskNames.PACKAGE_ASSEMBLY, ASSEMBLY_RUN);
        }
        return artifactFresh(dir, BuildLayout::assemblyJar)
                ? SKIP
                : learnedFixedWeight(dir.toString(), TaskNames.PACKAGE_ASSEMBLY, ASSEMBLY_RUN);
    }

    /**
     * Native binary/library weight for the progress bar and plan denominator — evaluated at
     * plan-start {@link cc.jumpkick.run.BuildPlan#estimatedTotalWeight} so calibrate sees the full
     * slice up front (bar never grows mid-run, never goes backwards).
     *
     * <p><b>Jar dirty ⇒ native dirty.</b> Never SKIP while the main jar will be rewritten.
     */
    public static int nativeWeight(Path dir) {
        if (nativeWillChange(dir)) {
            return nativeRunWeight(dir);
        }
        return SKIP;
    }

    /**
     * Native-image bar/ETA weight via {@link NativeEffort}: own wall → size-normalized host/product
     * model → host absolute only when size unknown → cold calibrated baseline.
     */
    public static int nativeRunWeight(Path dir) {
        return NativeEffort.weight(dir);
    }

    /**
     * OCI image weight. <b>Jar dirty or native dirty ⇒ OCI dirty</b>; never SKIP while either
     * packaging input will change.
     */
    public static int ociWeight(Path dir) {
        if (ociWillChange(dir)) {
            return learnedFixedWeight(dir.toString(), TaskNames.WRITE_IMAGE, OCI_RUN);
        }
        return OCI_SKIP;
    }

    /** Main jar will be rewritten this run (or dirty-module over-reserve). */
    public static boolean jarWillChange(Path dir) {
        return overReserveTails() || mainJarWillChange(dir);
    }

    /**
     * Native image will rebuild this run. <b>Jar dirty ⇒ always true</b> (impossible for the jar to
     * be dirty while native is clean). Also true when the binary is missing or older than the jar.
     */
    public static boolean nativeWillChange(Path dir) {
        if (jarWillChange(dir)) return true;
        return nativeOutputStaleOrMissing(dir);
    }

    /**
     * OCI image will rebuild this run. <b>Jar dirty or native dirty ⇒ always true</b> when those
     * products exist for the module. Also true when the OCI tarball is missing or older than the
     * jar.
     */
    public static boolean ociWillChange(Path dir) {
        if (jarWillChange(dir)) return true;
        if (producesNativeImage(dir) && nativeOutputStaleOrMissing(dir)) return true;
        return !artifactFresh(dir, BuildLayout::ociImageTar);
    }

    /** Binary/library missing or older than main jar (does not re-check jar dirtiness). */
    static boolean nativeOutputStaleOrMissing(Path dir) {
        return !(artifactFresh(dir, BuildLayout::nativeBinary) || artifactFresh(dir, BuildLayout::nativeLibrary));
    }

    /** {@code [native] enabled = "always"} — module may produce a native-image tail. */
    static boolean producesNativeImage(Path dir) {
        try {
            if (dir == null || !Files.isDirectory(dir)) return false;
            JkBuild project = JkBuildParser.parse(dir.resolve(ManifestPaths.MANIFEST));
            return project.nativeMode() == JkBuild.NativeMode.ALWAYS;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * True when the module's main jar is expected to be rewritten this run: force/rebuild, missing
     * jar, or main sources not stamp-fresh. Downstream packaging (native / assembly / OCI) must
     * reserve full weight when this is true — even if their outputs still look newer than the
     * pre-build jar.
     */
    static boolean mainJarWillChange(Path dir) {
        try {
            var cfg = SessionContext.current().config();
            if (cfg.rebuildOr(false) || cfg.forceOr(false)) return true;
            if (dir == null || !Files.isDirectory(dir)) return true;
            JkBuild project = JkBuildParser.parse(dir.resolve(ManifestPaths.MANIFEST));
            BuildLayout layout = BuildLayout.of(dir, project);
            if (!Files.isRegularFile(layout.mainJar())) return true;
            boolean compact = ModuleLayout.isCompact(dir);
            // Java main sources
            List<Path> javaSrc =
                    CompileSupport.collectJavaSources(compact ? dir.resolve("src") : dir.resolve("src/main/java"));
            if (!javaSrc.isEmpty()
                    && !FreshnessStamp.looksFresh(layout.classesDir(), FreshnessStamp.JAVA_STAMP, javaSrc)) {
                return true;
            }
            // Kotlin
            List<Path> ktSrc = CompileSupport.collectKotlinSources(dir, compact);
            if (!ktSrc.isEmpty()
                    && !FreshnessStamp.looksFresh(layout.kotlinClassesDir(), FreshnessStamp.KOTLIN_STAMP, ktSrc)) {
                return true;
            }
            // Groovy (stamp in merged classes dir)
            List<Path> gvSrc = CompileSupport.collectGroovySources(dir, compact);
            if (!gvSrc.isEmpty()
                    && !FreshnessStamp.looksFresh(layout.classesDir(), FreshnessStamp.GROOVY_STAMP, gvSrc)) {
                return true;
            }
            return false;
        } catch (Exception e) {
            // Unparseable / I/O — over-reserve so the bar never drops a multi-minute step.
            return true;
        }
    }

    /**
     * True when the artifact selected by {@code artifact} exists, isn't being forced by {@code
     * --force}, and is at least as new as the main jar it's derived from — a cheap "this output is
     * up-to-date" proxy for the artifact-cache skip the step itself performs.
     *
     * <p>Callers that reserve progress weight for jar-derived steps must also check {@link
     * #mainJarWillChange} first — see {@link #nativeWeight}.
     */
    private static boolean artifactFresh(Path dir, Function<BuildLayout, Path> artifact) {
        try {
            if (SessionContext.current().config().rebuildOr(false)) return false;
            if (SessionContext.current().config().forceOr(false)) return false;
            JkBuild project = JkBuildParser.parse(dir.resolve(ManifestPaths.MANIFEST));
            BuildLayout layout = BuildLayout.of(dir, project);
            Path art = artifact.apply(layout);
            if (!Files.isRegularFile(art)) return false;
            Path mainJar = layout.mainJar();
            if (!Files.isRegularFile(mainJar)) return false; // no jar → not fresh; rebuild inputs first
            return Files.getLastModifiedTime(art).toMillis()
                    >= Files.getLastModifiedTime(mainJar).toMillis();
        } catch (Exception e) {
            return false;
        }
    }

    /** Fetch weight: 8 per artifact not already in the CAS (all of them under {@code  --force}). */
    private static int predictSync(BuildPlanner.Inputs in, Cas cas) {
        try {
            int perFetch = artifactFetchWeight();
            if (!Files.exists(in.lockFile())) return perFetch; // first run resolves+fetches
            Lockfile lock = LockfileReader.read(in.lockFile());
            int fetches = 0;
            for (Lockfile.Artifact a : lock.artifacts()) {
                String checksum = a.checksum();
                if (checksum == null) continue; // pom-only / path / git — nothing to fetch
                // Only artifacts missing from the CAS cost anything to sync. --force/--refresh does
                // NOT re-download blobs already present: the CAS is content-addressed (a stored
                // sha256 is byte-identical), so a forced build resolves entirely from local disk
                // it even succeeds offline. Reserving a per-artifact download here for cached deps
                // was the bug that made `jk explain --force` predict tens of seconds of phantom fetch.
                String hex = checksum.startsWith("sha256:") ? checksum.substring("sha256:".length()) : checksum;
                if (!cas.contains(hex)) fetches++;
            }
            return fetches == 0 ? SKIP : fetches * perFetch;
        } catch (Exception e) {
            return SKIP;
        }
    }

    /**
     * Per-artifact fetch weight: trimmed-mean remote download duration when learned, else static
     * {@link #ARTIFACT_FETCH}.
     */
    static int artifactFetchWeight() {
        return FetchTimings.weightUnits(ARTIFACT_FETCH, MS_PER_WEIGHT);
    }

    // --- parallel-aware wall-clock estimate ----------------------------------

    /**
     * One module's cost for the schedule estimate: its weight, its serialized test weight, and its
     * prereqs.
     */
    public record ModuleCost(Path dir, Set<Path> prereqs, int weight, int testWeight) {}

    /**
     * The {@link ModuleCost} of a prepared module plan: its total estimated bar weight, plus the
     * serialized {@code run-tests} slice pulled out on its own (the schedule estimate treats that
     * step as a cross-module serial bound). Shared by {@code jk build} and {@code jk explain} so
     * their wall-clock estimates are computed from the plan identically.
     */
    public static ModuleCost costOf(Path dir, Set<Path> prereqs, BuildPlan plan) {
        return costOf(dir, prereqs, plan, Set.of());
    }

    /**
     * As {@link #costOf(Path, Set, cc.jumpkick.run.BuildPlan)}, but charging {@link #SKIP} for steps
     * the forecast already determined are cached.
     *
     * <p>A plan's estimated weight is what the steps would cost if they all ran. Estimating a
     * build from that alone ignores the plan sitting right next to it: a workspace whose every
     * module was reported "Fully Cached" still advertised a full-build ETA — ~2s for a 1ms no-op on
     * two modules, ~11s on five. A single-module project looked fine only because one module's full
     * cost rounds to "&lt;1s".
     */
    public static ModuleCost costOf(Path dir, Set<Path> prereqs, BuildPlan plan, Set<String> cachedSteps) {
        int weight = 0;
        int testWeight = 0;
        for (Task step : plan.steps()) {
            int stepWeight;
            if (cachedSteps.contains(step.name())) {
                stepWeight = SKIP;
            } else {
                try {
                    stepWeight = step.estimateWeight();
                } catch (RuntimeException e) {
                    stepWeight = 0; // mirrors estimatedTotalWeight: a failing estimate costs nothing
                }
            }
            weight += stepWeight;
            if (step.name().equals(TaskNames.RUN_TESTS)) testWeight += stepWeight;
        }
        return new ModuleCost(dir, prereqs, weight, testWeight);
    }

    /**
     * Module cost from pre-computed weights shape memo / ETA-only path) — no plan
     * assembly. {@code testWeight} is the serial {@code run-tests} slice; 0 when unknown.
     */
    public static ModuleCost costOf(Path dir, Set<Path> prereqs, int weight, int testWeight) {
        return new ModuleCost(dir, prereqs, Math.max(0, weight), Math.max(0, testWeight));
    }

    /**
     * Estimate a build's wall-clock (ms) from per-module costs (Σ dirty step weights preferred).
     *
     * <p>Mirrors {@link WorkspaceScheduler}: a module may start only after every dirty prereq has
     * <em>fully</em> finished (compile + test + package), and at most {@code concurrency} modules
     * run at once. Serial ({@code -j1}) is the sum of module weights. When tests are serialized
     * across modules ({@code parallelTests == false}), the serial test-step sum is also a lower
     * bound (same as the live cross-module test gate).
     */
    public static long scheduleMillis(List<ModuleCost> mods, int concurrency, boolean serial, boolean parallelTests) {
        return scheduleMillis(mods, concurrency, serial, parallelTests, MS_PER_WEIGHT);
    }

    /**
     * As {@link #scheduleMillis(List, int, boolean, boolean)} but with an explicit weight→ms
     * conversion. Costs from measured step walls use {@link #flatWeight} so × {@link #MS_PER_WEIGHT}
     * round-trips to milliseconds.
     */
    public static long scheduleMillis(
            List<ModuleCost> mods, int concurrency, boolean serial, boolean parallelTests, long msPerWeight) {
        if (mods == null || mods.isEmpty()) return 0;
        long rate = Math.max(1, msPerWeight);
        // Shared first-ready schedule (matches WorkspaceScheduler admission).
        long weights = WorkSchedule.schedule(toWorkCosts(mods), concurrency, serial, parallelTests);
        return weights * rate;
    }

    /**
     * As {@link #scheduleMillis(List, int, boolean, boolean, long)} but with a <em>per-module</em>
     * weight→ms rate. A single workspace-wide rate mis-priced the common mixed case — a brand-new
     * module beside already-built ones: the whole estimate had to pick one rail, so either the new
     * module was priced at {@link #MS_PER_WEIGHT} (the reference machine, ~4× hot on a fast host,
     * because its static reference-frame weights don't encode this host) or the built modules were
     * priced at the calibration rate (wrong for them — their learned rates already round-trip at 150).
     * Pricing each module by its own learned-ness fixes both at once: a warm module (learned rates for
     * its dir) converts at 150; a cold module converts at this host's measured calibration.
     *
     * <p>Implemented by pre-scaling each module's weights into ms-space with its own rate, then
     * running the identical schedule model at 1 ms/unit — so the critical-path / throughput /
     * serial-test bounds compose across modules that were priced differently.
     */
    public static long scheduleMillis(
            List<ModuleCost> mods,
            int concurrency,
            boolean serial,
            boolean parallelTests,
            ToDoubleFunction<Path> msPerWeightForModule) {
        List<ModuleCost> inMs = new ArrayList<>(mods.size());
        for (ModuleCost m : mods) {
            double r = msPerWeightForModule.applyAsDouble(m.dir());
            inMs.add(new ModuleCost(m.dir(), m.prereqs(), scaleToMs(m.weight(), r), scaleToMs(m.testWeight(), r)));
        }
        return scheduleMillis(inMs, concurrency, serial, parallelTests, 1L);
    }

    /** Weight × rate, rounded and clamped to a positive int (ms-space) so the schedule math can't overflow. */
    private static int scaleToMs(int weight, double rate) {
        long ms = Math.round(weight * Math.max(0.0, rate));
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, ms));
    }

    /** Convert engine costs to the shared schedule DTO (preserves list order). */
    public static List<ModuleWorkCost> toWorkCosts(List<ModuleCost> mods) {
        if (mods == null || mods.isEmpty()) return List.of();
        List<ModuleWorkCost> out = new ArrayList<>(mods.size());
        for (ModuleCost m : mods) {
            if (m == null || m.dir() == null) continue;
            out.add(new ModuleWorkCost(m.dir(), m.prereqs(), m.weight(), m.testWeight()));
        }
        return out;
    }

    /**
     * Rolling-window list schedule matching {@link WorkspaceScheduler} (first-ready admission).
     *
     * @return scheduled duration in the same units as {@link ModuleCost#weight}
     */
    static long listSchedule(List<ModuleCost> mods, int concurrency) {
        return WorkSchedule.schedule(toWorkCosts(mods), concurrency, false, true);
    }
}
