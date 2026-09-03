// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.run.TaskNames;
import java.util.Collection;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Prices the {@code run-tests} task for progress weights and ETA.
 *
 * <p>Ladder (first hit wins): the <strong>module-own normalized suite wall</strong> re-scheduled for
 * this build's runner count ({@link TestSuiteScaling}); then the module-own raw wall with a bounded
 * runner nudge, for history written before normalization existed; then <strong>Σ class walls</strong>
 * when every selected class has one (no method count required); then a full-suite Σ of all known
 * class walls; then methods × hierarchical method-ms (module → project → host → calibration
 * baseline) + startup. Matches the ladder table in {@code docs/perf/progress-contract.md}.
 *
 * <p>Every own-wall tier prices the <em>whole</em> suite, which is right: jk selects tests per
 * module, not per class, so a dirty module runs everything it has.
 */
public final class TestEffort {

    private TestEffort() {}

    /**
     * Weight units for run-tests.
     *
     * @param moduleDir module path string (ledger key)
     * @param classWallsMs measured walls for FQCNs (may be empty)
     * @param classesToRun FQCNs expected to run this time; when non-empty and every entry has a wall
     *     in {@code classWallsMs}, methodCount is ignored — but a module-own suite wall still
     *     outranks the class sum (a selection-priced caller gets full-suite walls; see class doc)
     * @param methodCount successful-method estimate; used only when class coverage is incomplete;
     *     pass 0 when unknown (do not invent a count)
     * @param testWorkers within-module workers for cold parallel body only
     */
    public static int weight(
            String moduleDir,
            Map<String, Long> classWallsMs,
            Collection<String> classesToRun,
            int methodCount,
            StepTimings timings,
            Collection<String> projectDirs,
            BuildMetrics metrics,
            int testWorkers) {
        long wallMs = wallMillis(
                moduleDir, classWallsMs, classesToRun, methodCount, timings, projectDirs, metrics, testWorkers);
        return wallMs > 0 ? EffortWeights.flatWeight(wallMs) : EffortWeights.TOKEN;
    }

    /** Absolute wall-ms estimate (before weight conversion). */
    public static long wallMillis(
            String moduleDir,
            Map<String, Long> classWallsMs,
            Collection<String> classesToRun,
            int methodCount,
            StepTimings timings,
            Collection<String> projectDirs,
            BuildMetrics metrics,
            int testWorkers) {
        long startup = suiteStartupMs();
        // This module's own measured suite wall first — always beats methodCount × cold method-ms
        // for a known module. (Host-tier suite average is NOT a substitute: it mixes tiny and huge
        // suites and under-prices a cold 1000-test module.)
        if (metrics != null && moduleDir != null && !moduleDir.isBlank()) {
            // The normalized wall first: it is the only stored suite cost that means the same thing
            // across runs that sharded the suite differently. See TestSuiteScaling.
            long wall1 = metrics.stepWall1Millis(moduleDir, TaskNames.RUN_TESTS);
            if (wall1 > 0) return TestSuiteScaling.forRunners(wall1, testWorkers);
            long own = EffortWeights.stepOkAvgMillisOwn(metrics, moduleDir, TaskNames.RUN_TESTS);
            if (own > 0) {
                return rescaleForRunners(own, metrics.stepWorkers(moduleDir, TaskNames.RUN_TESTS), testWorkers);
            }
        }
        // Class-wall path: complete selection coverage → Σ walls (no method count).
        if (classesToRun != null && !classesToRun.isEmpty() && classWallsMs != null && !classWallsMs.isEmpty()) {
            long sum = 0;
            boolean complete = true;
            int n = 0;
            for (String fqcn : classesToRun) {
                if (fqcn == null || fqcn.isBlank()) continue;
                n++;
                Long w = classWallsMs.get(fqcn);
                if (w == null || w <= 0) {
                    complete = false;
                    break;
                }
                sum += w;
            }
            if (complete && n > 0 && sum > 0) return startup + sum;
        }
        // Full-suite prior: no selection FQCNs and no method count → Σ all known class walls.
        if ((classesToRun == null || classesToRun.isEmpty())
                && methodCount <= 0
                && classWallsMs != null
                && !classWallsMs.isEmpty()) {
            long sum = 0;
            for (Long w : classWallsMs.values()) {
                if (w != null && w > 0) sum += w;
            }
            if (sum > 0) return startup + sum;
        }
        int methods = Math.max(0, methodCount);
        if (methods <= 0) {
            // Unknown size: host whole-task wall is better than inventing a method count.
            if (metrics != null) {
                long host = EffortWeights.stepOkAvgMillisHost(metrics, TaskNames.RUN_TESTS);
                if (host > 0) return host;
            }
            return Math.max(startup, Calibration.STATIC_SUITE_STARTUP_MS);
        }
        // Known method count on a cold module → hierarchical method-ms product (not host suite avg).
        double perMethod = methodMs(moduleDir, timings, projectDirs);
        int w = Math.max(1, testWorkers);
        long body = Math.round(methods * perMethod);
        long parallelBody = w <= 1 ? body : (body + w - 1) / w;
        return startup + parallelBody;
    }

    /** Most a rescale may stretch a recorded wall. */
    private static final long RESCALE_UP_CAP = 2;

    /** Most a rescale may shrink a recorded wall — sharding is never linear. */
    private static final long RESCALE_DOWN_CAP = 4;

    /**
     * Re-schedule a stored suite wall for the runners this build will hand the module — the
     * <strong>legacy</strong> path, for modules whose history predates {@code wall1-ms}.
     *
     * <p>Prefer {@link TestSuiteScaling}, which is reached above this. The difference is where the
     * normalization happens: {@code TestSuiteScaling} normalizes at record time, while the wall and
     * its runner count still belong to the same build, whereas this has to work from two independent
     * recency-weighted means. That mismatch is not a small inaccuracy — a 12 s wall averaged from
     * sharded runs beside a runner mean of 8 implies 96 s of "work" that nothing ever took — so the
     * correction here is deliberately a bounded nudge rather than an arithmetic identity: never more
     * than {@value #RESCALE_UP_CAP}x up, never below a {@value #RESCALE_DOWN_CAP}th. {@code ScheduleBias}
     * absorbs what is left. Once every module in a project has been through one successful build,
     * this stops being consulted.
     *
     * <p>{@code ranWith <= 0} means the record predates the concurrency being written at all; the
     * wall is returned unchanged rather than guessed at.
     */
    static long rescaleForRunners(long wallMs, int ranWith, int testWorkers) {
        if (wallMs <= 0 || ranWith <= 0) return wallMs;
        int runners = Math.max(1, testWorkers);
        if (ranWith == runners) return wallMs;
        long scaled = Math.round(wallMs * (ranWith / (double) runners));
        long floor = Math.max(1, wallMs / RESCALE_DOWN_CAP);
        long ceiling = wallMs * RESCALE_UP_CAP;
        return Math.max(floor, Math.min(ceiling, scaled));
    }

    /** Hierarchical method-ms: module residual → project median → host absolute → calibration. */
    public static double methodMs(String moduleDir, StepTimings timings, Collection<String> projectDirs) {
        if (timings != null) {
            var own = timings.perUnit(moduleDir == null ? "" : moduleDir, TaskNames.RUN_TESTS);
            if (own.isPresent() && own.getAsDouble() > 0) {
                return own.getAsDouble() * EffortWeights.MS_PER_WEIGHT;
            }
            // Project median before host absolute: sibling modules share frameworks/fixtures, a
            // strictly closer prior than a host-wide average that may come from other projects.
            if (projectDirs != null && !projectDirs.isEmpty()) {
                var proj = timings.medianPerUnit(TaskNames.RUN_TESTS, projectDirs);
                if (proj.isPresent() && proj.getAsDouble() > 0) {
                    return proj.getAsDouble() * EffortWeights.MS_PER_WEIGHT;
                }
            }
            OptionalDouble hostAbs = timings.hostAvgTestMethodMs();
            if (hostAbs.isPresent()) return hostAbs.getAsDouble();
            var hostRate = timings.medianPerUnit(TaskNames.RUN_TESTS);
            if (hostRate.isPresent() && hostRate.getAsDouble() > 0) {
                return hostRate.getAsDouble() * EffortWeights.MS_PER_WEIGHT;
            }
        }
        try {
            Calibration cal = Calibration.load();
            var learned = cal.learned().meanMs(HostLearnedRates.RUN_TESTS_PER_METHOD_MS);
            if (learned.isPresent()) return learned.getAsDouble();
            return Calibration.scaleBaseline(Calibration.BASELINE_METHOD_MS, 1.0);
        } catch (RuntimeException e) {
            return Calibration.BASELINE_METHOD_MS;
        }
    }

    static long suiteStartupMs() {
        try {
            Calibration cal = Calibration.load();
            var learned = cal.learned().meanMs(HostLearnedRates.RUN_TESTS_SUITE_STARTUP_MS);
            if (learned.isPresent()) return Math.round(learned.getAsDouble());
            if (cal.hasColdPriors()) {
                long wall0 = cal.coldStepWallMs(TaskNames.RUN_TESTS, 0, 1);
                if (wall0 > 0) return wall0;
            }
            return Math.round(Calibration.scaleBaseline(Calibration.BASELINE_SUITE_STARTUP_MS, 1.0));
        } catch (RuntimeException e) {
            return Calibration.STATIC_SUITE_STARTUP_MS;
        }
    }
}
