// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import cc.jumpkick.host.time.Clock;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The wall bound on one file's formatting.
 *
 * <p>A line-break search is not linear in a file's size: a deeply nested expression can hold one
 * thread inside a 7&nbsp;KB source for minutes. Unbounded, such a run has no failure and no output —
 * the pool settles three thousand files and then waits on the one thread nobody can name.
 *
 * <p>Two thresholds, both measured from the moment a {@linkplain #watch window} opens. Past
 * {@code warnMs} the file is {@linkplain Slow named} while it is still in flight; past
 * {@code timeoutMs} it is abandoned — a {@linkplain #verdict verdict} recorded against its spec
 * index, its thread interrupted, and {@code onAbandon} run so the caller can replace the slot the
 * run just lost. Either threshold at {@code 0} or below is off.
 *
 * <p>An interrupt cannot stop CPU-bound work that never checks for one, so what these thresholds
 * bound is the <em>run</em>, not the thread: the abandoned thread may keep a core busy until the
 * worker process exits. That is why the pool's threads are daemons and why a lost slot is replaced.
 */
final class FormatWatchdog implements AutoCloseable {

    /** Named while one file is still in flight, so a long peg is never silent. */
    interface Slow {
        void inFlight(File file, long elapsedMs);
    }

    /**
     * Chatter threshold. The largest real sources in a mixed Java tree format in ~100&nbsp;ms warm,
     * so half a second means something is unusual without meaning something is wrong.
     */
    static final long DEFAULT_WARN_MS = 500;

    /**
     * Kill threshold. The slowest single file across a 3,000-file Java and Kotlin tree is ~120&nbsp;ms,
     * and ~785&nbsp;ms with the pool deliberately oversubscribed eight to one — a shape the run never
     * chooses for itself, since {@code CodeFormatter.concurrency} sizes it to the visible cores. Two
     * seconds is therefore not a slow file; it is a break search that has stopped tracking the size
     * of its source. {@code jk.format.file-timeout-ms} raises it for a host that disagrees.
     */
    static final long DEFAULT_TIMEOUT_MS = 2_000;

    private static final long MIN_TICK_MS = 25;
    private static final long MAX_TICK_MS = 250;

    private final long warnNanos;
    private final long timeoutNanos;
    private final long timeoutMs;
    private final Clock clock;
    private final Slow slow;
    private final Runnable onAbandon;

    /** Spec index → open window. A window leaves this map exactly once, by close or by abandon. */
    private final ConcurrentMap<Integer, Watch> inFlight = new ConcurrentHashMap<>();

    private final ConcurrentMap<Integer, String> verdicts = new ConcurrentHashMap<>();

    private volatile Thread ticker;

    FormatWatchdog(long warnMs, long timeoutMs, Clock clock, Slow slow, Runnable onAbandon) {
        this.warnNanos = warnMs * 1_000_000L;
        this.timeoutNanos = timeoutMs * 1_000_000L;
        this.timeoutMs = timeoutMs;
        this.clock = clock;
        this.slow = slow;
        this.onAbandon = onAbandon;
    }

    /**
     * Open the bound for the file at spec index {@code index}, on the calling thread. Close it when
     * that file is done; nothing outside the window is timed.
     */
    Watch watch(int index, File file) {
        Watch w = new Watch(index, file, Thread.currentThread(), clock.nanos());
        inFlight.put(index, w);
        return w;
    }

    /** Why the file at {@code index} was abandoned, or null while it is still the run's to finish. */
    String verdict(int index) {
        return verdicts.get(index);
    }

    /** Start the ticker. A no-op when both thresholds are off. */
    void start() {
        if (warnNanos <= 0 && timeoutNanos <= 0) return;
        long period = tickMs();
        Thread t = new Thread(
                () -> {
                    while (true) {
                        try {
                            Thread.sleep(period);
                        } catch (InterruptedException e) {
                            return;
                        }
                        tick();
                    }
                },
                "jk-format-watchdog");
        t.setDaemon(true);
        ticker = t;
        t.start();
    }

    @Override
    public void close() {
        Thread t = ticker;
        if (t != null) t.interrupt();
    }

    /** One sweep of the open windows. Package-private so a test can drive it against a fake clock. */
    void tick() {
        long now = clock.nanos();
        for (Watch w : inFlight.values()) {
            long elapsed = now - w.startNanos;
            if (timeoutNanos > 0 && elapsed >= timeoutNanos) {
                abandon(w, elapsed);
            } else if (warnNanos > 0 && elapsed >= w.nextNoticeNanos) {
                // Doubling off the elapsed time, not off the previous threshold: a file that is
                // pegged for minutes is named ~10 times, not 800.
                w.nextNoticeNanos = elapsed * 2;
                slow.inFlight(w.file, millis(elapsed));
            }
        }
    }

    private void abandon(Watch w, long elapsed) {
        // Loses to the window's own close, so a file that finished inside the same tick keeps its
        // real verdict and its thread keeps the interrupt it never needed.
        if (!inFlight.remove(w.index, w)) return;
        verdicts.put(w.index, "timed out after " + human(millis(elapsed)) + " (limit " + timeoutMs + " ms)");
        w.thread.interrupt();
        onAbandon.run();
    }

    private long tickMs() {
        long smallest = warnNanos > 0 && timeoutNanos > 0
                ? Math.min(warnNanos, timeoutNanos)
                : Math.max(warnNanos, timeoutNanos);
        return Math.clamp(millis(smallest) / 4, MIN_TICK_MS, MAX_TICK_MS);
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000L;
    }

    /** A duration for a person: milliseconds under a second, one decimal of seconds above it. */
    static String human(long ms) {
        return ms < 1000 ? ms + " ms" : String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }

    /** One file's open window. The thread that opened it closes it. */
    final class Watch implements AutoCloseable {

        private final int index;
        private final File file;
        private final Thread thread;
        private final long startNanos;
        private volatile long nextNoticeNanos = warnNanos;

        private Watch(int index, File file, Thread thread, long startNanos) {
            this.index = index;
            this.file = file;
            this.thread = thread;
            this.startNanos = startNanos;
        }

        @Override
        public void close() {
            inFlight.remove(index, this);
            // Whatever interrupt this watchdog delivered dies with the window, so a pool thread that
            // survived one does not carry it into the next file.
            Thread.interrupted();
        }
    }
}
