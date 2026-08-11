// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.progress;

/**
 * Snapshot of inputs every header progress strategy needs to paint (immutable view of
 * {@link cc.jumpkick.cli.tui.CommandManager} fields at paint time).
 */
public record HeaderProgressState(
        /** Last engine weight-slice numerator (may be 0). */
        long weightNumerator,
        /** Last engine weight-slice denominator (0 = none yet). */
        long weightDenominator,
        /**
         * Open-loop seed remaining at seed time in ms ({@code -1} unknown, {@code 0}+ seeded).
         * Same R0 as the countdown.
         */
        long r0Ms,
        /** Elapsed ms when R0 was taken (for {@code elapsedSinceSeed}). */
        long seedAtElapsedMs,
        /** Current run-wide elapsed ms. */
        long elapsedMs,
        /** True after settle — strategies may return 100%. */
        boolean settled) {

    /** Elapsed since the R0 seed was taken (never negative). */
    public long elapsedSinceSeed() {
        if (r0Ms < 0) return 0;
        return Math.max(0L, elapsedMs - seedAtElapsedMs);
    }

    /** True when a positive wall seed is available for open-loop paint. */
    public boolean hasR0() {
        return r0Ms > 0;
    }
}
