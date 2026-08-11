// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.progress;

/** Immutable inputs for {@link HeaderProgressStrategy#display}. */
public record HeaderProgressState(
        long weightNumerator,
        long weightDenominator,
        /** Open-loop seed remaining at seed time ({@code -1} unknown). */
        long r0Ms,
        /** Elapsed ms when R0 was taken (run-relative or wall-delta base). */
        long seedAtElapsedMs,
        /** Current elapsed ms on the same clock as {@link #seedAtElapsedMs}. */
        long elapsedMs,
        boolean settled) {

    public long elapsedSinceSeed() {
        if (r0Ms < 0) return 0;
        return Math.max(0L, elapsedMs - seedAtElapsedMs);
    }

    public boolean hasR0() {
        return r0Ms > 0;
    }
}
