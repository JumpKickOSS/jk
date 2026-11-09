// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime.progress;

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
        boolean settled,
        /** Elapsed ms when {@link #residualRemainingMs} was taken (same clock as {@link #elapsedMs}). */
        long residualAtElapsedMs,
        /** Modules not yet finished ({@code -1} unknown): the residual counts down only for the last. */
        int pendingModules) {

    /**
     * How far a residual counts down between samples: to a quarter of itself. The engine re-samples
     * at step boundaries, so a build's last module holds its residual for its whole compile; the bar
     * then crept while the countdown beside it ran down. Counted all the way to zero, a step that
     * overran its estimate pinned the bar at the cap until it finished. With more modules pending,
     * their completions re-sample it, and a countdown between samples ratcheted the peak-held bar
     * ahead: 13 points at 75 % of a Helidon cold build.
     */
    private static final long DECAY_FLOOR_DIVISOR = 4;

    /** A residual taken now: nothing has counted down yet. */
    public HeaderProgressState(
            long weightNumerator,
            long weightDenominator,
            long r0Ms,
            long seedAtElapsedMs,
            long elapsedMs,
            long residualRemainingMs,
            boolean settled) {
        this(
                weightNumerator,
                weightDenominator,
                r0Ms,
                seedAtElapsedMs,
                elapsedMs,
                residualRemainingMs,
                settled,
                elapsedMs,
                -1);
    }

    /**
     * While the last module runs, the residual counted down by the time since it was taken, as the
     * countdown beside the bar runs it, but never below a quarter of it; otherwise the residual as
     * taken; {@code -1} when there is none.
     */
    public long residualNowMs() {
        if (residualRemainingMs < 0) return -1;
        if (pendingModules < 0 || pendingModules > 1) return residualRemainingMs;
        long since = Math.max(0L, elapsedMs - residualAtElapsedMs);
        return Math.max(residualRemainingMs - since, residualRemainingMs / DECAY_FLOOR_DIVISOR);
    }

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
