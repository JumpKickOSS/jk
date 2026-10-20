// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import org.jspecify.annotations.Nullable;

/**
 * The beat every live region polls on: a daemon thread that runs {@code beat} every {@code
 * intervalMs} until {@link #stop}. A beat asks the region for its frame; the region's {@link
 * cc.jumpkick.terminal.RegionPainter} writes nothing when nothing changed, so a fast clock costs
 * no bytes while the screen is still.
 */
public final class FrameClock {

    /** The animation cadence of every live region. */
    public static final long FRAME_MS = Spinner.FRAME_MS;

    private final String name;
    private final long intervalMs;
    private final Runnable beat;
    private volatile boolean stopped;
    private @Nullable Thread thread;

    private final boolean beatFirst;

    private FrameClock(String name, long intervalMs, Runnable beat, boolean beatFirst) {
        this.name = name;
        this.intervalMs = intervalMs;
        this.beat = beat;
        this.beatFirst = beatFirst;
    }

    /** Start a clock that runs {@code beat} once per {@code intervalMs}, first after one interval. */
    public static FrameClock start(String name, long intervalMs, Runnable beat) {
        return start(name, intervalMs, beat, false);
    }

    /** Start a clock; with {@code beatFirst} the first beat runs at once rather than after an interval. */
    public static FrameClock start(String name, long intervalMs, Runnable beat, boolean beatFirst) {
        FrameClock clock = new FrameClock(name, intervalMs, beat, beatFirst);
        Thread t = new Thread(clock::run, name);
        t.setDaemon(true);
        clock.thread = t;
        t.start();
        return clock;
    }

    /** Whether {@link #stop} was asked for. */
    boolean stopped() {
        return stopped;
    }

    /**
     * Stop the clock. Idempotent, and safe under the region's lock: it does not wait for a beat in
     * flight, so every beat checks its region's own closed flag under that lock before painting.
     */
    public void stop() {
        stopped = true;
        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) t.interrupt();
    }

    private void run() {
        try {
            if (beatFirst && !stopped) beat.run();
            while (!stopped) {
                Thread.sleep(intervalMs);
                if (stopped) return;
                beat.run();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String toString() {
        return "FrameClock[" + name + "]";
    }
}
