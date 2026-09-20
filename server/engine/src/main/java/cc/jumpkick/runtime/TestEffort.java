// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.BuildMetrics;
import cc.jumpkick.runtime.base.HostLearnedRates;
import cc.jumpkick.runtime.base.StepTimings;
import cc.jumpkick.wire.runtime.TestSuiteScaling;
import java.util.Collection;
import java.util.Map;
import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;

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
            @Nullable StepTimings timings,
            Collection<String> projectDirs,
            @Nullable BuildMetrics metrics,
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
            @Nullable StepTimings timings,
            Collection<String> projectDirs,
            @Nullable BuildMetrics metrics,
            int testWorkers) {
        return wallMillis(
                moduleDir,
                classWallsMs,
                classesToRun,
                methodCount,
                timings,
                projectDirs,
                metrics,
                testWorkers,
                loadedCalibration());
    }

    /**
     * The estimate with the host calibration handed in rather than read from {@code JK_HOME}.
     *
     * <p>Both cold rungs end at the calibration — {@link #suiteStartupMs} and the last arm of {@link
     * #methodMs} — so loading it inside makes them a function of whatever this machine has learned. A
     * caller that means to exercise a cold ladder has to supply the rung it is pricing against;
     * otherwise it measures the host, which is a different question and not a stable one.
     */
    static long wallMillis(
            String moduleDir,
            Map<String, Long> classWallsMs,
            Collection<String> classesToRun,
            int methodCount,
            @Nullable StepTimings timings,
            Collection<String> projectDirs,
            @Nullable BuildMetrics metrics,
            int testWorkers,
            @Nullable Calibration cal) {
        long startup = suiteStartupMs(cal);
        // This module's own measured suite wall first — always beats methodCount × cold method-ms
        // for a known module. (Host-tier suite average is NOT a substitute: it mixes tiny and huge
        // suites and under-prices a cold 1000-test module.)
        if (metrics != null && moduleDir != null && !moduleDir.isBlank()) {
            // The normalized wall is the only stored suite cost that means the same thing across
            // runs that sharded the suite differently. See TestSuiteScaling. A module with a raw
            // wall but no normalized one has no usable suite history and is priced from its
            // classes or methods below.
            long wall1 = metrics.stepWall1Millis(moduleDir, TaskNames.RUN_TESTS);
            if (wall1 > 0) return TestSuiteScaling.forRunners(wall1, testWorkers);
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
                long host = StepWalls.stepOkAvgMillisHost(metrics, TaskNames.RUN_TESTS);
                if (host > 0) return host;
            }
            return Math.max(startup, Calibration.STATIC_SUITE_STARTUP_MS);
        }
        // Known method count on a cold module → hierarchical method-ms product (not host suite avg).
        double perMethod = methodMs(moduleDir, timings, projectDirs, cal);
        int w = Math.max(1, testWorkers);
        long body = Math.round(methods * perMethod);
        long parallelBody = w <= 1 ? body : (body + w - 1) / w;
        return startup + parallelBody;
    }

    /** Hierarchical method-ms: module residual → project median → host absolute → calibration. */
    public static double methodMs(String moduleDir, @Nullable StepTimings timings, Collection<String> projectDirs) {
        return methodMs(moduleDir, timings, projectDirs, loadedCalibration());
    }

    /** {@link #methodMs} with the calibration rung supplied; {@code null} means fall to the baseline. */
    static double methodMs(
            String moduleDir,
            @Nullable StepTimings timings,
            Collection<String> projectDirs,
            @Nullable Calibration cal) {
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
        if (cal == null) return Calibration.BASELINE_METHOD_MS;
        var learned = cal.learned().meanMs(HostLearnedRates.RUN_TESTS_PER_METHOD_MS);
        if (learned.isPresent()) return learned.getAsDouble();
        return Calibration.scaleBaseline(Calibration.BASELINE_METHOD_MS, 1.0);
    }

    /** The host calibration, or {@code null} when it cannot be read — the cold rungs then use the baseline. */
    private static @Nullable Calibration loadedCalibration() {
        try {
            return Calibration.load();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static long suiteStartupMs() {
        return suiteStartupMs(loadedCalibration());
    }

    /** {@link #suiteStartupMs} against a supplied calibration; {@code null} means the baseline. */
    static long suiteStartupMs(@Nullable Calibration cal) {
        if (cal == null) return Math.round(Calibration.scaleBaseline(Calibration.BASELINE_SUITE_STARTUP_MS, 1.0));
        try {
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
