// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

/**
 * Workspace/pipeline aggregate progress snapshot for JSONL riders (JK-1117).
 *
 * <p>Updated whenever the human bar model changes ({@link AggregateContext}, single-pipeline
 * {@link CommandManagerListener}, etc.). Machine output attaches a single {@code progress}
 * percent (0–100) — never raw numerator/denominator on the rider.
 *
 * <p>Materialize cadence constants (JK-1118) live here so TTY and disk/stdout share one source.
 */
public final class LiveProgress {

    /** TTY live-region frame interval ({@code CommandManager} / Spinner). */
    public static final long TTY_FRAME_MS = 80;

    /** Disk JSONL flush heartbeat when only hot ticks dirty the file. */
    public static final long DISK_HEARTBEAT_MS = 2000;

    /** Partial process-line stale flush (TTY only; not session JSONL). */
    public static final long LINE_STALE_MS = 360;

    private static final LiveProgress INSTANCE = new LiveProgress();

    /** Last known percent, or {@code null} until the first meaningful denominator. */
    private volatile Double percent;

    private LiveProgress() {}

    public static LiveProgress get() {
        return INSTANCE;
    }

    /** Clear between commands so a prior run does not leak into the next. */
    public void clear() {
        percent = null;
    }

    /**
     * Update from bar units. No-op when {@code denominator <= 0}. Percent is clamped to 0–100 and
     * rounded to one decimal place.
     */
    public void update(long numerator, long denominator) {
        if (denominator <= 0) return;
        double raw = 100.0 * (double) numerator / (double) denominator;
        if (raw < 0) raw = 0;
        if (raw > 100) raw = 100;
        // One decimal: 42.5, not binary noise.
        percent = Math.round(raw * 10.0) / 10.0;
    }

    /** Explicit percent (tests / session-finish at 100). */
    public void setPercent(Double value) {
        if (value == null) {
            percent = null;
            return;
        }
        double v = value;
        if (v < 0) v = 0;
        if (v > 100) v = 100;
        percent = Math.round(v * 10.0) / 10.0;
    }

    /** Current percent, or {@code null} if unknown. */
    public Double percent() {
        return percent;
    }

    /**
     * JSON number token for the rider: {@code null}, an integer, or one-decimal number.
     */
    public String jsonToken() {
        Double p = percent;
        if (p == null) return "null";
        double v = p;
        if (v == Math.rint(v)) return Long.toString((long) v);
        return Double.toString(v);
    }
}
