// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.builds.AggregatedMetrics;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.base.BuildMetrics;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * One step's recorded wall, read from the harvested ledger for {@link EffortWeights}: this
 * module's own row, the host's row, or nothing. Every caller picks its own fallback from there.
 */
@NullMarked
public final class StepWalls {

    private StepWalls() {}

    /** Minimum successful runs before a metrics average outranks a static constant (bar weights). */
    public static final int MIN_METRICS_SAMPLES = 3;

    /**
     * Success-only average wall for one module step (ms), from this module's own history; 0 when it
     * never ran the step here. Count ≥ 1 is enough — ETA composes dirty steps from measured pieces,
     * not whole-build priors. Every caller picks its own fallback, so there is deliberately no
     * combiner that tries own-then-host for them.
     */
    public static long stepOkAvgMillisOwn(@Nullable BuildMetrics metrics, String dir, String step) {
        String key = EffortWeights.metricsStepName(step);
        if (key.isEmpty()) return 0;
        long fromAgg = stepWallFromAggregates(dir == null ? "" : dir, key);
        if (fromAgg > 0) return fromAgg;
        if (metrics == null) metrics = BuildMetrics.load(BuildMetrics.defaultFile());
        var own = metrics.step(dir == null ? "" : dir, key);
        if (own.isPresent() && own.get().ok().count() >= 1 && own.get().ok().avgMillis() > 0) {
            return own.get().ok().avgMillis();
        }
        return 0;
    }

    /** Host tier: the cross-module average wall for {@code step}, when this module has no history. */
    public static long stepOkAvgMillisHost(BuildMetrics metrics, String step) {
        String key = EffortWeights.metricsStepName(step);
        if (key.isEmpty()) return 0;
        long fromAgg = stepWallFromAggregates("", key);
        if (fromAgg > 0) return fromAgg;
        if (metrics == null) metrics = BuildMetrics.load(BuildMetrics.defaultFile());
        var host = metrics.step("", key);
        if (host.isPresent() && host.get().ok().count() >= 1 && host.get().ok().avgMillis() > 0) {
            return host.get().ok().avgMillis();
        }
        return 0;
    }

    /**
     * One step's wall from the harvested ledger: the trimmed mean once the row has two samples,
     * the single sample when it has one.
     *
     * <p>The mean, not the last sample, on purpose. The last sample of a step is whichever build
     * ran it most recently, and on a full rebuild that is the most contended measurement the ledger
     * holds: the dogfood engine's test compile reads a 6.8 s mean beside a 28 s last, taken while 24
     * modules compiled at once. A critical-path schedule adds such walls serially along the spine.
     * A structural speed-up that leaves the mean stale is what {@code ScheduleBias} absorbs.
     *
     * <p>Heavy steps carry a credibility floor ({@link #heavyWallFloorMs}): a native-image or
     * write-image wall under it is a cache-restore blip and prices nothing.
     */
    public static long stepWallFromAggregates(String dir, String step) {
        try {
            var agg = BuildMetrics.aggregatesForSession();
            String task = EffortWeights.metricsStepName(step);
            if (task.isEmpty()) return 0;
            String key;
            if (dir == null || dir.isBlank()) {
                key = "task." + task + ".wall-ms";
            } else {
                key = "module." + AggregatedMetrics.sanitize(dir) + ".task." + task + ".wall-ms";
            }
            Double meanRow = agg.meanMap().get(key);
            Double lastRow = agg.lastMap().get(key);
            double mean = meanRow == null ? 0 : meanRow;
            double last = lastRow == null ? 0 : lastRow;
            long floor = heavyWallFloorMs(task);
            boolean meanCredible = mean > 0 && mean >= floor;
            boolean lastCredible = last > 0 && last >= floor;
            if (meanCredible && agg.count(key) >= 2) return Math.round(mean);
            if (lastCredible) return Math.round(last);
            if (meanCredible) return Math.round(mean);
            return 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Metrics-learned flat weight for fixed-cost steps: module avg → host avg → {@code
     * staticWeight}. Success-only samples.
     */
    public static int learnedFixedWeight(String dir, String step, int staticWeight) {
        return learnedFixedWeight(BuildMetrics.load(BuildMetrics.defaultFile()), dir, step, staticWeight);
    }

    public static int learnedFixedWeight(BuildMetrics metrics, String dir, String step, int staticWeight) {
        // Heavy IO steps (native-image, OCI) are rare and long — one successful wall is enough
        // to beat the cold floor; waiting for 3 samples left the bar on a 15s token for months.
        int minSamples = heavyFixedStep(step) ? 1 : MIN_METRICS_SAMPLES;
        long floorMs = heavyFixedStep(step) ? heavyWallFloorMs(step) : 0;
        var own = metrics.step(dir, EffortWeights.metricsStepName(step));
        if (own.isPresent() && own.get().ok().count() >= minSamples) {
            long avg = own.get().ok().avgMillis();
            // Ignore poisoned cache-restore samples (e.g. native-image "32ms" success).
            if (avg >= floorMs) return EffortWeights.flatWeight(avg);
        }
        var host = metrics.step("", EffortWeights.metricsStepName(step));
        if (host.isPresent() && host.get().ok().count() >= minSamples) {
            long avg = host.get().ok().avgMillis();
            if (avg >= floorMs) return EffortWeights.flatWeight(avg);
        }
        return staticWeight;
    }

    /** Reject absurdly short measured walls for heavy steps (action-cache restore noise). */
    private static long heavyWallFloorMs(String step) {
        String s = EffortWeights.metricsStepName(step);
        if (TaskNames.NATIVE_IMAGE.equals(s)) return 5_000L;
        if (TaskNames.WRITE_IMAGE.equals(s)) return 3_000L;
        return 0L;
    }

    private static boolean heavyFixedStep(String step) {
        String s = EffortWeights.metricsStepName(step);
        return TaskNames.NATIVE_IMAGE.equals(s)
                || TaskNames.WRITE_IMAGE.equals(s)
                || TaskNames.PACKAGE_ASSEMBLY.equals(s);
    }
}
