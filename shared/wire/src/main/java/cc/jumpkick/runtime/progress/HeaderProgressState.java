// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.progress;

/** Immutable inputs for {@link HeaderProgressStrategy#display}. */
public record HeaderProgressState(
        long weightNumerator,
        long weightDenominator,
        /**
         * Seed remaining at seed time ({@code -1} unknown). Countdown starts from this; residual
         * re-anchors mid-run on the client.
         */
        long r0Ms,
        /** Elapsed ms when R0 was taken (same clock as {@link #elapsedMs}). */
        long seedAtElapsedMs,
        /** Current elapsed ms on the same clock as {@link #seedAtElapsedMs}. */
        long elapsedMs,
        /**
         * Live residual remaining wall estimate ({@code -1} = unknown). When known, clock fill is
         * {@code elapsed / (elapsed + residual)} so the bar ends with residual → 0. The client
         * countdown re-anchors to the same residual.
         */
        long residualRemainingMs,
        boolean settled) {

    public long elapsedSinceSeed() {
        // Residual-only (forced clock with no R0 seed): elapsed still advances from the tracker
        // base, otherwise the clock fill froze at 0% forever.
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
