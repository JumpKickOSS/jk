// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * How a suite's clock follows the share of its class walls done: the share of its wall that had
 * passed when 10%, 20% ... 90% of its class walls were done, in thousandths. Classes run side by
 * side and the last few alone, so the class share is not the time share: the engine's suite was
 * 84% done by class walls at 71% of its wall, and read at its rate it had 6 s left of 13. The curve
 * is the suite's own and holds from one run of the same shape to the next (the engine's read 27,
 * 45, 65 and 81% of its wall at a quarter, half, three quarters and 90% of its class walls in every
 * jk test run of the 2026-11-12 corpus), so the last run's curve reads the class share as time.
 */
public final class SuiteCurve {

    private SuiteCurve() {}

    /** Points of a curve: the class shares 0.1 to 0.9. */
    public static final int POINTS = 9;

    /**
     * The curve of a finished suite from its progress points, each {@code {classShare, timeShare}}
     * in the order the classes finished; {@code null} when the points do not reach every decile.
     */
    public static int @Nullable [] of(List<double[]> points) {
        int[] curve = new int[POINTS];
        int next = 0;
        for (double[] p : points) {
            while (next < POINTS && p[0] >= (next + 1) / 10.0) {
                curve[next++] = (int) Math.round(1000 * Math.max(0, Math.min(1, p[1])));
            }
        }
        if (next < POINTS) return null;
        for (int i = 1; i < POINTS; i++) curve[i] = Math.max(curve[i], curve[i - 1]);
        return curve;
    }

    /** The share of the suite's wall {@code curve} reads at the class share {@code share}. */
    public static double timeShare(int @Nullable [] curve, double share) {
        if (curve == null || curve.length != POINTS) return share;
        double s = Math.max(0, Math.min(1, share));
        int i = (int) Math.floor(s * 10);
        if (i >= 10) return 1;
        double lo = i == 0 ? 0 : curve[i - 1] / 1000.0;
        double hi = i == POINTS ? 1 : curve[i] / 1000.0;
        return lo + (s * 10 - i) * (hi - lo);
    }
}
