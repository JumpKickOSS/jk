// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.util.Collection;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Prices the {@code run-tests} task for progress weights and ETA.
 *
 * <p><strong>Prefer class walls</strong> when every selected class has a measured wall — then the
 * estimate is suite-startup + Σ class walls and <em>no method count is required</em>. Otherwise use
 * methods × hierarchical method-ms (module → project → host → calibration baseline) + startup.
 */
public final class TestEffort {

    private TestEffort() {}

    /**
     * Weight units for run-tests.
     *
     * @param moduleDir module path string (ledger key)
     * @param classWallsMs measured walls for FQCNs (may be empty)
     * @param classesToRun FQCNs expected to run this time; when non-empty and every entry has a wall
     *     in {@code classWallsMs}, methodCount is ignored
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
            java.util.Collection<String> projectDirs,
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
            java.util.Collection<String> projectDirs,
            BuildMetrics metrics,
            int testWorkers) {
        long startup = suiteStartupMs();
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
        // Whole-task wall for this module beats a cold method product when available.
        if (metrics != null && moduleDir != null && !moduleDir.isBlank()) {
            long own = EffortWeights.stepOkAvgMillisOwn(metrics, moduleDir, "run-tests");
            if (own > 0) return own;
        }
        int methods = Math.max(0, methodCount);
        if (methods <= 0) {
            if (metrics != null) {
                long host = EffortWeights.stepOkAvgMillisHost(metrics, "run-tests");
                if (host > 0) return host;
            }
            return Math.max(startup, Calibration.STATIC_SUITE_STARTUP_MS);
        }
        double perMethod = methodMs(moduleDir, timings, projectDirs);
        int w = Math.max(1, testWorkers);
        long body = Math.round(methods * perMethod);
        long parallelBody = w <= 1 ? body : (body + w - 1) / w;
        return startup + parallelBody;
    }

    /** Hierarchical method-ms: module residual → project median → host absolute → calibration. */
    public static double methodMs(String moduleDir, StepTimings timings, java.util.Collection<String> projectDirs) {
        if (timings != null) {
            var own = timings.perUnit(moduleDir == null ? "" : moduleDir, "run-tests");
            if (own.isPresent() && own.getAsDouble() > 0) {
                return own.getAsDouble() * EffortWeights.MS_PER_WEIGHT;
            }
            // Project median before host absolute: sibling modules share frameworks/fixtures, a
            // strictly closer prior than a host-wide average that may come from other projects.
            if (projectDirs != null && !projectDirs.isEmpty()) {
                var proj = timings.medianPerUnit("run-tests", projectDirs);
                if (proj.isPresent() && proj.getAsDouble() > 0) {
                    return proj.getAsDouble() * EffortWeights.MS_PER_WEIGHT;
                }
            }
            OptionalDouble hostAbs = timings.hostAvgTestMethodMs();
            if (hostAbs.isPresent()) return hostAbs.getAsDouble();
            var hostRate = timings.medianPerUnit("run-tests");
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
                long wall0 = cal.coldStepWallMs("run-tests", 0, 1);
                if (wall0 > 0) return wall0;
            }
            return Math.round(Calibration.scaleBaseline(Calibration.BASELINE_SUITE_STARTUP_MS, 1.0));
        } catch (RuntimeException e) {
            return Calibration.STATIC_SUITE_STARTUP_MS;
        }
    }
}
