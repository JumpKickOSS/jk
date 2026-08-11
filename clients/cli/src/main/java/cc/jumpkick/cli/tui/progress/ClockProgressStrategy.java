// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.progress;

/**
 * Open-loop wall fill: {@code min(99%, elapsedSinceSeed / R0)} until settle → 100%. Same oracle as
 * the countdown. Never goes backwards (peak hold). Without R0, returns empty den (caller should
 * not select this strategy — {@link ProgressBarMode#AUTO} falls back to weighted).
 */
public final class ClockProgressStrategy implements HeaderProgressStrategy {

    /** Cap while the build is still running; settle forces 100%. */
    public static final double DISPLAY_CAP = 0.99;

    /** Synthetic denominator for stable percent paint. */
    private static final long SCALE = 1000L;

    private double peakFraction;

    @Override
    public long[] display(HeaderProgressState state) {
        if (!state.hasR0()) return new long[] {0, 0};
        long r0 = state.r0Ms();
        if (state.settled()) return new long[] {SCALE, SCALE};
        double raw = (double) state.elapsedSinceSeed() / (double) r0;
        if (raw < 0) raw = 0;
        if (raw > DISPLAY_CAP) raw = DISPLAY_CAP;
        if (raw < peakFraction) raw = peakFraction;
        else peakFraction = raw;
        return new long[] {Math.round(raw * SCALE), SCALE};
    }

    @Override
    public long[] onWeightProgress(HeaderProgressState state, long numerator, long denominator) {
        // Weights do not drive open-loop fill; paint from elapsed/R0 only.
        return display(state);
    }

    @Override
    public String id() {
        return "clock";
    }

    /** Test seam / reset when a new command starts. */
    public void reset() {
        peakFraction = 0;
    }
}
