// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.progress;

/**
 * Adaptive open-loop wall fill for the progress bar.
 *
 * <p><b>Countdown</b> stays pure open-loop {@code R0 − elapsed} (not this class).
 *
 * <p><b>Bar</b> uses a private residual remaining estimate when available:
 *
 * <pre>
 *   frac = elapsed / (elapsed + residualRemaining)
 * </pre>
 *
 * so the fill speeds up when work finishes faster than R0 and slows when residual grows — never
 * rewrites the public countdown. Without residual, falls back to {@code elapsed / R0}. Cap 99%
 * until settle; never goes backwards.
 */
public final class ClockProgressStrategy implements HeaderProgressStrategy {

    public static final double DISPLAY_CAP = 0.99;
    private static final long SCALE = 1000L;

    /** Cross-strategy monotonic floor — shared with the paired weighted strategy (JK-1815). */
    private final SharedPeak peak;

    public ClockProgressStrategy() {
        this(new SharedPeak());
    }

    public ClockProgressStrategy(SharedPeak peak) {
        this.peak = peak == null ? new SharedPeak() : peak;
    }

    @Override
    public long[] display(HeaderProgressState state) {
        if (!state.hasR0() && !state.hasResidual()) return new long[] {0, 0};
        if (state.settled()) return new long[] {SCALE, SCALE};

        long elapsed = state.elapsedSinceSeed();
        double raw;
        if (state.hasResidual()) {
            // Adaptive: remaining work firming up mid-run (private estimate only).
            long residual = Math.max(0L, state.residualRemainingMs());
            long denom = elapsed + residual;
            if (denom <= 0) raw = DISPLAY_CAP;
            else raw = (double) elapsed / (double) denom;
        } else {
            // Pure open-loop until residual is available.
            raw = (double) elapsed / (double) state.r0Ms();
        }
        if (raw < 0) raw = 0;
        if (raw > DISPLAY_CAP) raw = DISPLAY_CAP;
        raw = peak.raise(raw);
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
        peak.reset();
    }
}
