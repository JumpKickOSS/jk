// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver.pubgrub;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.time.Clock;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The solver's time budget, measured as progress rather than wall clock: a solve is stopped only
 * when no decision, version catalog read or POM read has advanced for one window, however long the
 * whole solve takes. The window is {@value #DEFAULT_WINDOW_MS} ms unless {@code
 * JK_RESOLVE_TIMEOUT_MS} says otherwise; {@code 0} never stops a solve.
 *
 * <p>A stalled solver thread is usually parked inside a source read, where no budget check runs, so
 * a watcher thread samples the progress counter and interrupts the solver once the window passes
 * without a change. The solver then reads {@link #stall()} for the sentence naming what it was
 * doing, and {@link #stop()} clears the interrupt so the thread leaves the solve as it entered it.
 */
final class StallWatch {

    /** Default stall window in milliseconds. */
    static final long DEFAULT_WINDOW_MS = 120_000L;

    /** Longest pause between two samples of the progress counter. */
    private static final long MAX_TICK_MS = 1_000L;

    private final Clock clock;
    private final long windowNanos;
    private final LongSupplier progress;
    private final Supplier<String> phase;
    private final Object lock = new Object();
    private @Nullable Thread solver;
    private @Nullable Thread watcher;
    private boolean stopped;
    private volatile @Nullable String stall;

    /**
     * @param windowMs how long {@code progress} may stand still before the solve is stopped; {@code
     *     <= 0} never stops it
     * @param progress a counter that moves with every decision and every read the source completes
     * @param phase what the solver is doing right now, read when the window passes
     */
    StallWatch(Clock clock, long windowMs, LongSupplier progress, Supplier<String> phase) {
        this.clock = clock;
        this.windowNanos = windowMs <= 0 ? 0L : TimeUnit.MILLISECONDS.toNanos(windowMs);
        this.progress = progress;
        this.phase = phase;
    }

    /** The stall window from {@code JK_RESOLVE_TIMEOUT_MS}, else {@link #DEFAULT_WINDOW_MS}. */
    static long envWindowMs() {
        String v = System.getenv("JK_RESOLVE_TIMEOUT_MS");
        if (v == null || v.isBlank()) return DEFAULT_WINDOW_MS;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_WINDOW_MS;
        }
    }

    /** Watch {@code solver} until {@link #stop}; nothing is started for a zero window. */
    void start(Thread solver) {
        if (windowNanos == 0L) return;
        synchronized (lock) {
            this.solver = solver;
            this.stopped = false;
            this.watcher = SessionContext.startVirtual("jk-resolve-stall-watch", this::watch);
        }
    }

    private void watch() {
        long tickMs = Math.max(1L, Math.min(MAX_TICK_MS, TimeUnit.NANOSECONDS.toMillis(windowNanos) / 4));
        long last = progress.getAsLong();
        long lastAdvance = clock.nanos();
        while (true) {
            try {
                Thread.sleep(tickMs);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (lock) {
                if (stopped) return;
                long now = progress.getAsLong();
                if (now != last) {
                    last = now;
                    lastAdvance = clock.nanos();
                    continue;
                }
                if (clock.nanos() - lastAdvance < windowNanos) continue;
                stall = "no decision, version catalog read or POM read advanced for "
                        + TimeUnit.NANOSECONDS.toSeconds(windowNanos)
                        + " s while "
                        + phase.get()
                        + " (after "
                        + now
                        + " decisions and reads); set JK_RESOLVE_TIMEOUT_MS to change the stall window (0 = never)";
                if (solver != null) solver.interrupt();
                return;
            }
        }
    }

    /**
     * Stop watching. Called on the solver thread when the solve returns: an interrupt this watch
     * delivered is cleared here, so it never reaches whatever the thread does next.
     */
    void stop() {
        synchronized (lock) {
            stopped = true;
            if (watcher != null) watcher.interrupt();
            if (stall != null) Thread.interrupted();
        }
    }

    /** True once the window passed without progress and the solver was interrupted. */
    boolean tripped() {
        return stall != null;
    }

    /** What stalled and for how long, in a sentence the user can act on; empty before {@link #tripped}. */
    String stall() {
        String s = stall;
        return s == null ? "" : s;
    }
}
