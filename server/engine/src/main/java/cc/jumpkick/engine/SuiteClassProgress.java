// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.runtime.EffortWeights;
import cc.jumpkick.runtime.base.BuildMetrics;
import cc.jumpkick.runtime.base.SuiteCurve;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A running suite's progress as the share of its class walls done: each class at its recorded wall
 * ({@link BuildMetrics#classWallsForSession}), a class with no record at the wall it just took. The
 * suite's ticks count classes, which differ in length by orders of magnitude; their walls do not
 * lie about which part of the suite is left.
 */
final class SuiteClassProgress {

    /** No measure of the suite's progress: its classes have no recorded walls. */
    static final double UNKNOWN = -1;

    private final Map<String, Long> expected;
    private double total;
    private double done;
    private final Map<String, Boolean> finished = new HashMap<>();
    /** When the suite started on the build's clock; negative until {@link #started}. */
    private long startMillis = -1;
    /** Each finished class's {@code {share, atMillis}}, for the suite's {@link SuiteCurve}. */
    private final List<double[]> points = new ArrayList<>();

    SuiteClassProgress(Map<String, Long> expected) {
        this.expected = expected;
        for (long ms : expected.values()) total += Math.max(0, ms);
    }

    /**
     * The progress of {@code moduleDir}'s suite kept under {@code suiteStep}, the run's selection
     * ({@code run-tests@all_integration}): its class walls are that tier's, not the default tier's.
     */
    static SuiteClassProgress of(String moduleDir, String suiteStep) {
        return new SuiteClassProgress(EffortWeights.loadClassWalls(moduleDir, suiteStep));
    }

    /**
     * {@code className} finished in {@code millis}; the suite's share done (0..1), or {@link #UNKNOWN}
     * when the suite has no recorded class to measure against: a share of classes nobody has timed
     * says nothing about the rest, and read as a share the first class would finish the suite.
     */
    synchronized double finished(String className, long millis) {
        return finished(className, millis, -1);
    }

    /** {@link #finished(String, long)}, the class finishing at {@code atMillis} on the build's clock. */
    synchronized double finished(String className, long millis, long atMillis) {
        if (expected.isEmpty()) return UNKNOWN;
        if (finished.put(className, Boolean.TRUE) != null) return share();
        Long known = expected.get(className);
        if (known != null && known > 0) done += known;
        else {
            done += Math.max(0, millis);
            total += Math.max(0, millis);
        }
        double share = share();
        if (atMillis >= 0) points.add(new double[] {share, atMillis});
        return share;
    }

    /**
     * {@code {sum, longest}} of the suite's recorded class walls: how far more test JVMs can shorten
     * it ({@link cc.jumpkick.wire.runtime.StepCost#scale(int, int, long, long)}); zeros when none are recorded.
     */
    synchronized long[] classWalls() {
        long sum = 0, longest = 0;
        for (long ms : expected.values()) {
            sum += Math.max(0, ms);
            longest = Math.max(longest, ms);
        }
        return new long[] {sum, longest};
    }

    /** {@code className}'s recorded wall; {@code 0} when it has none. */
    synchronized long expected(String className) {
        Long ms = expected.get(className);
        return ms == null ? 0 : Math.max(0, ms);
    }

    /** The suite started at {@code atMillis} on the build's clock. */
    synchronized void started(long atMillis) {
        if (startMillis < 0) startMillis = atMillis;
    }

    /**
     * The finished suite's curve ({@link SuiteCurve}), the suite having ended at {@code endMillis};
     * {@code null} when its start or its classes' walls are unknown.
     */
    synchronized int @Nullable [] curve(long endMillis) {
        long wall = endMillis - startMillis;
        if (startMillis < 0 || wall <= 0 || points.isEmpty()) return null;
        List<double[]> shares = new ArrayList<>();
        for (double[] p : points) shares.add(new double[] {p[0], (p[1] - startMillis) / wall});
        return SuiteCurve.of(shares);
    }

    private double share() {
        return total <= 0 ? 0 : Math.min(1.0, done / total);
    }
}
