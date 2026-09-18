// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.time.Clock;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A time budget measured as progress rather than wall clock: the work is stopped only when its
 * progress counter has not moved for one window, however long the whole takes. The solver counts
 * decisions and reads; an import counts the POMs it read. The window is {@value #DEFAULT_WINDOW_MS}
 * ms unless {@code JK_RESOLVE_TIMEOUT_MS} says otherwise; {@code 0} never stops the work.
 *
 * <p>A stalled thread is usually parked inside a read, where no budget check runs, so a watcher
 * thread samples the progress counter and interrupts the worker once the window passes without a
 * change. The worker then reads {@link #stall()} for the sentence naming what it was doing and the
 * URL it was waiting on, and {@link #stop()} clears the interrupt so the thread leaves the work as
 * it entered it.
 */
public final class StallWatch {

    /** Default stall window in milliseconds. */
    public static final long DEFAULT_WINDOW_MS = 120_000L;

    /** What advances the solver's counter, for its stall sentence. */
    public static final String SOLVER_ADVANCES = "no decision, version catalog read or POM read";

    /** Longest pause between two samples of the progress counter. */
    private static final long MAX_TICK_MS = 1_000L;

    private final Clock clock;
    private final long windowNanos;
    private final String advances;
    private final LongSupplier progress;
    private final Supplier<String> phase;
    private final Supplier<String> waitingOn;
    private final Object lock = new Object();
    private @Nullable Thread solver;
    private @Nullable Thread watcher;
    private boolean stopped;
    private volatile @Nullable String stall;

    /**
     * @param windowMs how long {@code progress} may stand still before the work is stopped; {@code
     *     <= 0} never stops it
     * @param advances what moves {@code progress}, as the stall sentence's subject ({@code no POM read})
     * @param progress a counter that moves with every unit of work completed
     * @param phase what the worker is doing right now, read when the window passes
     * @param waitingOn the URL(s) a read is parked on, read when the window passes; empty for none
     */
    public StallWatch(
            Clock clock,
            long windowMs,
            String advances,
            LongSupplier progress,
            Supplier<String> phase,
            Supplier<String> waitingOn) {
        this.clock = clock;
        this.windowNanos = windowMs <= 0 ? 0L : TimeUnit.MILLISECONDS.toNanos(windowMs);
        this.advances = advances;
        this.progress = progress;
        this.phase = phase;
        this.waitingOn = waitingOn;
    }

    /** The stall window from {@code JK_RESOLVE_TIMEOUT_MS}, else {@link #DEFAULT_WINDOW_MS}. */
    public static long envWindowMs() {
        String v = System.getenv("JK_RESOLVE_TIMEOUT_MS");
        if (v == null || v.isBlank()) return DEFAULT_WINDOW_MS;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_WINDOW_MS;
        }
    }

    /** Watch {@code solver} until {@link #stop}; nothing is started for a zero window. */
    public void start(Thread solver) {
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
                String parked = waitingOn.get();
                stall = advances + " advanced for "
                        + TimeUnit.NANOSECONDS.toSeconds(windowNanos)
                        + " s while "
                        + phase.get()
                        + " (after "
                        + now
                        + " completed)"
                        + (parked.isEmpty() ? "" : "; " + parked)
                        + "; set JK_RESOLVE_TIMEOUT_MS to change the stall window (0 = never)";
                if (solver != null) solver.interrupt();
                return;
            }
        }
    }

    /**
     * Stop watching. Called on the watched thread when its work returns: an interrupt this watch
     * delivered is cleared here, so it never reaches whatever the thread does next.
     */
    public void stop() {
        synchronized (lock) {
            stopped = true;
            if (watcher != null) watcher.interrupt();
            if (stall != null) Thread.interrupted();
        }
    }

    /** True once the window passed without progress and the worker was interrupted. */
    public boolean tripped() {
        return stall != null;
    }

    /** What stalled and for how long, in a sentence the user can act on; empty before {@link #tripped}. */
    public String stall() {
        String s = stall;
        return s == null ? "" : s;
    }
}
