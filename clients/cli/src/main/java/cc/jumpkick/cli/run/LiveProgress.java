// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.run;

/**
 * Workspace/pipeline aggregate progress snapshot for JSONL riders.
 *
 * <p>For multi-module builds, updated only from engine {@code workspace-progress} (via {@link
 * AggregateContext#applySnapshot}). Single-pipeline paths still update from {@link
 * CommandManagerListener}. Machine output attaches a single {@code progress} percent (0–100)
 * never raw numerator/denominator on the rider.
 *
 * <p>Materialize cadence constants live here; the TTY frame interval is {@link
 * cc.jumpkick.runtime.WorkspaceProgressTracker#TTY_FRAME_MS} (shared with the engine emit throttle).
 */
public final class LiveProgress {

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
     * rounded to one decimal ({@link cc.jumpkick.runtime.WorkspaceProgressTracker#percentOf}).
     */
    public void update(long numerator, long denominator) {
        double p = cc.jumpkick.runtime.WorkspaceProgressTracker.percentOf(numerator, denominator);
        if (!Double.isNaN(p)) percent = p;
    }

    /**
     * Apply an engine workspace snapshot — the only aggregate truth for multi-module builds.
     * Percent-only snapshots (denominator 0) still land.
     */
    public void apply(cc.jumpkick.runtime.WorkspaceProgressTracker.Snapshot snap) {
        if (snap == null) return;
        if (snap.denominator() > 0) update(snap.numerator(), snap.denominator());
        else if (snap.hasPercent()) setPercent(snap.percent());
    }

    /** Explicit percent (tests / session-finish at 100). */
    public void setPercent(Double value) {
        percent = value == null ? null : cc.jumpkick.runtime.WorkspaceProgressTracker.clampPercent(value);
    }

    /** Current percent, or {@code null} if unknown. */
    public Double percent() {
        return percent;
    }
}
