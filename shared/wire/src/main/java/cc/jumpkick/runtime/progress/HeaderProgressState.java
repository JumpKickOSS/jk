// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.progress;

/** Immutable inputs for {@link HeaderProgressStrategy#display}. */
public record HeaderProgressState(
        long weightNumerator,
        long weightDenominator,
        /** Open-loop seed remaining at seed time ({@code -1} unknown). Countdown uses this only. */
        long r0Ms,
        /** Elapsed ms when R0 was taken (same clock as {@link #elapsedMs}). */
        long seedAtElapsedMs,
        /** Current elapsed ms on the same clock as {@link #seedAtElapsedMs}. */
        long elapsedMs,
        /**
         * Private residual remaining wall estimate for the bar ({@code -1} = unknown). Updated as
         * work completes; never shown as the countdown. When known, clock fill is {@code elapsed /
         * (elapsed + residual)} so the bar can speed up or slow down without rewriting R0.
         */
        long residualRemainingMs,
        boolean settled) {

    public long elapsedSinceSeed() {
        // Residual-only (forced clock with no R0 seed): elapsed still advances from the tracker
        // base, otherwise the clock fill froze at 0% forever (JK-1816).
        if (r0Ms < 0 && residualRemainingMs < 0) return 0;
        return Math.max(0L, elapsedMs - seedAtElapsedMs);
    }

    public boolean hasR0() {
        return r0Ms > 0;
    }

    /** True when a residual schedule remaining is available for adaptive clock fill. */
    public boolean hasResidual() {
        return residualRemainingMs >= 0;
    }
}
