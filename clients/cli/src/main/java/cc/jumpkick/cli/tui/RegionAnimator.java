// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import org.jspecify.annotations.Nullable;

/**
 * Run exactly one of the live region's two {@link FrameClock}s — the ANSI frame beat, or the
 * {@code --no-ansi} heartbeat that wakes about once a second to emit a 30 s stage line — and stop it
 * once; a second stop is a no-op.
 *
 * <p>Receives the manager's monitor and takes it exactly where the manager did: around the heartbeat
 * beat and around handing off the thread. The frame loop's stale-line flush runs <em>outside</em>
 * that monitor on purpose, so write order matches step writes (sink → lock) and cannot deadlock
 * with a frame paint, which takes the lock alone.
 */
final class RegionAnimator {

    /** A captured partial line that has gone quiet this long is flushed above the region. */
    static final long STALE_FLUSH_MS = 360;

    private final Object lock;
    private final LongConsumer flushStale;
    private final Runnable frame;
    private final Runnable heartbeat;
    private final BooleanSupplier done;

    private volatile boolean stopped;
    private @Nullable FrameClock clock;

    /**
     * @param flushStale flushes a captured partial line that went quiet; runs outside {@code lock}
     * @param frame paints one frame; takes {@code lock} itself
     * @param heartbeat one plain-mode beat; called under {@code lock}
     * @param done whether a terminal render already happened; read under {@code lock}
     */
    RegionAnimator(Object lock, LongConsumer flushStale, Runnable frame, Runnable heartbeat, BooleanSupplier done) {
        this.lock = lock;
        this.flushStale = flushStale;
        this.frame = frame;
        this.heartbeat = heartbeat;
        this.done = done;
    }

    /** True once {@link #stop} has been asked for; the loops and the peek-key listener read it. */
    boolean stopped() {
        return stopped;
    }

    void startFrames() {
        clock = FrameClock.start("jk-command-manager", FrameClock.FRAME_MS, this::frameBeat, true);
    }

    /** Plain-mode clock: wake about once a second and emit a 30s stage heartbeat. */
    void startPlainHeartbeat() {
        clock = FrameClock.start("jk-plain-heartbeat", 1_000L, this::heartbeatBeat);
    }

    private void frameBeat() {
        // Flush a captured partial line that's gone quiet (no newline),
        // OUTSIDE the render lock so the order matches step writes
        // (sink → lock) and can't deadlock with tick (lock only).
        flushStale.accept(STALE_FLUSH_MS);
        frame.run();
    }

    private void heartbeatBeat() {
        synchronized (lock) {
            if (done.getAsBoolean() || stopped) return;
            heartbeat.run();
        }
    }

    /** Stop the clock. Idempotent. */
    void stop() {
        stopped = true;
        FrameClock c;
        synchronized (lock) {
            c = clock;
            clock = null;
        }
        if (c != null) c.stop();
    }
}
