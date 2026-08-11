// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.progress;

/** Effort-weight bar: engine plan numerator/denominator with monotonic peak hold. */
public final class WeightedProgressStrategy implements HeaderProgressStrategy {

    private long numerator;
    private long denominator;
    private double peakFraction;

    @Override
    public long[] display(HeaderProgressState state) {
        if (state.settled() && denominator > 0) return new long[] {denominator, denominator};
        long num = state.weightNumerator() > 0 || state.weightDenominator() > 0
                ? state.weightNumerator()
                : numerator;
        long den = state.weightDenominator() > 0 ? state.weightDenominator() : denominator;
        return applyPeak(num, den);
    }

    @Override
    public long[] onWeightProgress(HeaderProgressState state, long num, long den) {
        double f = den > 0 ? (double) num / (double) den : 0.0;
        if (den > this.denominator) {
            peakFraction = f;
        } else if (den > 0 && f < peakFraction) {
            num = Math.round(peakFraction * den);
        } else {
            peakFraction = f;
        }
        this.numerator = Math.max(0, num);
        this.denominator = Math.max(0, den);
        return new long[] {this.numerator, this.denominator};
    }

    private long[] applyPeak(long num, long den) {
        if (den <= 0) return new long[] {0, 0};
        double f = (double) num / (double) den;
        if (f < peakFraction) num = Math.round(peakFraction * den);
        else peakFraction = f;
        return new long[] {num, den};
    }

    @Override
    public String id() {
        return "weighted";
    }

    public void reset() {
        numerator = 0;
        denominator = 0;
        peakFraction = 0;
    }
}
