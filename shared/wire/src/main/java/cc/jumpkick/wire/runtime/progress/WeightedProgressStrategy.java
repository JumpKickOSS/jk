// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime.progress;

/**
 * Effort-weight bar: engine plan numerator/denominator with a monotonic displayed fraction.
 *
 * <p>The peak is fraction-space and shared with the paired clock strategy ({@link SharedPeak},
 * ), so neither a denominator growth (calibrate, mid-run reweight) nor an AUTO
 * clock-takeover can paint the bar backwards — the contract's "bar never goes backwards" is
 * absolute. When the denominator grows, the fraction holds at the floor until real
 * progress passes it; both {@link #display} and {@link #onWeightProgress} share the same clamp.
 */
public final class WeightedProgressStrategy implements HeaderProgressStrategy {

    private long numerator;
    private long denominator;

    /** Cross-strategy monotonic floor — shared with the paired clock strategy. */
    private final SharedPeak peak;

    public WeightedProgressStrategy() {
        this(new SharedPeak());
    }

    public WeightedProgressStrategy(SharedPeak peak) {
        this.peak = peak == null ? new SharedPeak() : peak;
    }

    @Override
    public long[] display(HeaderProgressState state) {
        if (state.settled() && denominator > 0) return new long[] {denominator, denominator};
        long num = state.weightNumerator() > 0 || state.weightDenominator() > 0 ? state.weightNumerator() : numerator;
        long den = state.weightDenominator() > 0 ? state.weightDenominator() : denominator;
        if (den <= 0) return new long[] {0, 0};
        return clampToPeak(num, den);
    }

    @Override
    public long[] onWeightProgress(HeaderProgressState state, long num, long den) {
        if (den <= 0) {
            this.numerator = Math.max(0, num);
            this.denominator = 0;
            return new long[] {this.numerator, 0};
        }
        long[] out = clampToPeak(num, den);
        this.numerator = out[0];
        this.denominator = out[1];
        return out;
    }

    private long[] clampToPeak(long num, long den) {
        double f = Math.max(0, (double) num / (double) den);
        double held = peak.raise(f);
        if (held > f) num = Math.round(held * den);
        return new long[] {Math.max(0, num), den};
    }

    @Override
    public String id() {
        return "weighted";
    }

    public void reset() {
        numerator = 0;
        denominator = 0;
        peak.reset();
    }
}
