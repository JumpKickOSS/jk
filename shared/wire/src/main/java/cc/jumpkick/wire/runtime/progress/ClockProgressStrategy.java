// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime.progress;

/**
 * Adaptive wall fill for the progress bar from the remaining work.
 *
 * <pre>
 *   frac = elapsed / (elapsed + remaining)
 * </pre>
 *
 * where {@code elapsed} runs from the start of the command — preflight included — and {@code
 * remaining} is the residual estimate when there is one, else R0 less the time since the seed's
 * anchor. Counted from the command's start, the bar at execute start reads preflight / (preflight
 * + R0), the wall fraction itself when R0 is right: on a 2 s incremental build the preflight is a
 * third of the wall, and a bar that credited it a fixed tenth read 18–40 points behind the clock.
 * Speeds up when work finishes faster than estimated, slows when it grows, reaches ~100% as the
 * remaining work reaches 0. Cap 99% until settle; never goes backwards.
 *
 * <p>Held at its peak, the bar still moves: when the remainder grows past what the peak implies (a
 * step running far past its price), it closes the gap to the cap by the share of the whole run
 * each repaint's interval is, {@code (cap - peak) * dt / (elapsed + remaining)}. A cold integration
 * run's suite priced at 54 s ran 638 s; peak-held, its bar sat still for 56% of the wall (at 99%
 * while the ETA read 3 s, JK-ZJ8D9). Replayed over jk's 2026-11-13 corpus, this pace held the cold
 * runs' longest plateau to a quarter of the wall and left the warm runs' bar error where it was;
 * a pace of {@code dt / (dt + remaining)} ran the gap down to the cap within minutes and stalled
 * there, and added up to 12 points of error to warm runs.
 */
public final class ClockProgressStrategy implements HeaderProgressStrategy {

    public static final double DISPLAY_CAP = 0.99;
    private static final long SCALE = 1000L;

    /** Cross-strategy monotonic floor — shared with the paired weighted strategy. */
    private final SharedPeak peak;

    public ClockProgressStrategy() {
        this(new SharedPeak());
    }

    public ClockProgressStrategy(SharedPeak peak) {
        this.peak = peak == null ? new SharedPeak() : peak;
    }

    /** The elapsed time at the last repaint, or -1 before the first: the interval a held bar advances by. */
    private long paintedAt = -1;

    @Override
    public synchronized long[] display(HeaderProgressState state) {
        if (!state.hasR0() && !state.hasResidual()) return new long[] {0, 0};
        if (state.settled()) return new long[] {SCALE, SCALE};

        long elapsed = Math.max(0L, state.elapsedMs());
        // Adaptive once the residual firms up mid-run, counted down between samples as the
        // countdown is; open-loop on R0 from its anchor until then.
        long remaining = state.hasResidual()
                ? Math.max(0L, state.residualNowMs())
                : Math.max(0L, state.r0Ms() - state.elapsedSinceSeed());
        long denom = elapsed + remaining;
        double raw = denom <= 0 ? DISPLAY_CAP : (double) elapsed / (double) denom;
        if (raw < 0) raw = 0;
        if (raw > DISPLAY_CAP) raw = DISPLAY_CAP;
        double held = peak.get();
        if (raw < held && paintedAt >= 0 && elapsed > paintedAt && remaining > 0) {
            long dt = elapsed - paintedAt;
            raw = Math.max(raw, held + (DISPLAY_CAP - held) * dt / (double) (elapsed + remaining));
        }
        if (elapsed > paintedAt) paintedAt = elapsed;
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

    public synchronized void reset() {
        paintedAt = -1;
        peak.reset();
    }
}
