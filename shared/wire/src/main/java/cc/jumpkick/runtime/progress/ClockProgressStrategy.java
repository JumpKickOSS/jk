// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.progress;

/**
 * Open-loop wall fill: {@code min(99%, elapsedSinceSeed / R0)} until settle → 100%. Same oracle as
 * the countdown. Never goes backwards.
 */
public final class ClockProgressStrategy implements HeaderProgressStrategy {

    public static final double DISPLAY_CAP = 0.99;
    private static final long SCALE = 1000L;

    private double peakFraction;

    @Override
    public long[] display(HeaderProgressState state) {
        if (!state.hasR0()) return new long[] {0, 0};
        if (state.settled()) return new long[] {SCALE, SCALE};
        double raw = (double) state.elapsedSinceSeed() / (double) state.r0Ms();
        if (raw < 0) raw = 0;
        if (raw > DISPLAY_CAP) raw = DISPLAY_CAP;
        if (raw < peakFraction) raw = peakFraction;
        else peakFraction = raw;
        return new long[] {Math.round(raw * SCALE), SCALE};
    }

    @Override
    public long[] onWeightProgress(HeaderProgressState state, long numerator, long denominator) {
        return display(state);
    }

    @Override
    public String id() {
        return "clock";
    }

    public void reset() {
        peakFraction = 0;
    }
}
