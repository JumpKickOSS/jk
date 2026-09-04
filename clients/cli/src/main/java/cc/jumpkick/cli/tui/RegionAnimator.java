// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import java.util.function.BooleanSupplier;

/**
 * Run exactly one of the live region's two background loops — the ANSI frame loop, or the
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
    private final OutputCapture capture;
    private final Runnable frame;
    private final Runnable heartbeat;
    private final BooleanSupplier done;

    private volatile boolean stopped;
    private Thread thread;

    /**
     * @param frame paints one frame; takes {@code lock} itself
     * @param heartbeat one plain-mode beat; called under {@code lock}
     * @param done whether a terminal render already happened; read under {@code lock}
     */
    RegionAnimator(Object lock, OutputCapture capture, Runnable frame, Runnable heartbeat, BooleanSupplier done) {
        this.lock = lock;
        this.capture = capture;
        this.frame = frame;
        this.heartbeat = heartbeat;
        this.done = done;
    }

    /** True once {@link #stop} has been asked for; the loops and the peek-key listener read it. */
    boolean stopped() {
        return stopped;
    }

    void startFrames() {
        thread = new Thread(this::frameLoop, "jk-command-manager");
        thread.setDaemon(true);
        thread.start();
    }

    /** Plain-mode background thread: wake about once a second and emit a 30s stage heartbeat. */
    void startPlainHeartbeat() {
        thread = new Thread(this::heartbeatLoop, "jk-plain-heartbeat");
        thread.setDaemon(true);
        thread.start();
    }

    private void frameLoop() {
        try {
            while (!stopped) {
                // Flush a captured partial line that's gone quiet (no newline),
                // OUTSIDE the render lock so the order matches step writes
                // (sink → lock) and can't deadlock with tick (lock only).
                capture.flushStale(STALE_FLUSH_MS);
                frame.run();
                Thread.sleep(JkManager.FRAME_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void heartbeatLoop() {
        try {
            while (!stopped) {
                Thread.sleep(1_000L);
                synchronized (lock) {
                    if (done.getAsBoolean() || stopped) return;
                    heartbeat.run();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stop the loop and join it briefly. Idempotent. */
    void stop() {
        stopped = true;
        Thread a;
        synchronized (lock) {
            a = thread;
            thread = null;
        }
        if (a != null) {
            a.interrupt();
            try {
                a.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
