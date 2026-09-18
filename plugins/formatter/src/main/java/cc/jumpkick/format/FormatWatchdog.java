// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import cc.jumpkick.host.time.Clock;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

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
 * index and {@code onAbandon} run so the caller can replace the slot the run just lost. Either
 * threshold at {@code 0} or below is off.
 *
 * <p>Nothing here stops the thread: a line-break search never checks for an interrupt, so what
 * these thresholds bound is the <em>run</em>, not the thread. The abandoned thread may keep a core
 * busy until the worker process exits — which is why the pool's threads are daemons and why a lost
 * slot is replaced — and the verdict is what keeps its late result from counting.
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
     * Kill threshold, and deliberately more generous than measurement alone would ask for.
     *
     * <p>What the measurements say is the <em>shape</em> of the risk, not the number. The slowest
     * single file across a 3,000-file Java and Kotlin tree is ~120&nbsp;ms, ~160&nbsp;ms with no
     * worker AOT cache (a fresh host, cold JIT), and ~150&nbsp;ms with the engine pinned to two
     * cores — because {@code CodeFormatter.concurrency} sizes the pool to the visible cores, so a
     * small host lengthens the <em>run</em> rather than its files. Only eight-to-one
     * oversubscription, which the run never chooses for itself, reaches ~785&nbsp;ms.
     *
     * <p>All of which is one machine. A fractional-vCPU runner, a host with bad I/O, or a
     * multi-megabyte generated source are not in that sample, and the point of the ceiling is to be
     * generous where the evidence is thin. Three seconds is a judgement, not a reading. It costs a
     * pathological file one extra second exactly once, because the verdict is remembered
     * ({@code FormatStampCache}), and it leaves a file that is merely on a bad host alone.
     *
     * <p>The default is the quiet host's; {@link FormatTimeout#forHost} stretches it by the load per
     * online processor, and {@code jk.format.file-timeout-ms} sets it outright either way.
     */
    static final long DEFAULT_TIMEOUT_MS = 3_000;

    private static final long MIN_TICK_MS = 25;
    private static final long MAX_TICK_MS = 250;

    private final long warnNanos;
    private final long timeoutNanos;
    private final FormatTimeout timeout;
    private final Clock clock;
    private final Slow slow;
    private final Runnable onAbandon;

    /** Spec index → open window. A window leaves this map exactly once, by close or by abandon. */
    private final ConcurrentMap<Integer, Watch> inFlight = new ConcurrentHashMap<>();

    private final ConcurrentMap<Integer, String> verdicts = new ConcurrentHashMap<>();

    private volatile @Nullable Thread ticker;

    FormatWatchdog(long warnMs, FormatTimeout timeout, Clock clock, Slow slow, Runnable onAbandon) {
        this.warnNanos = warnMs * 1_000_000L;
        this.timeoutNanos = timeout.ms() * 1_000_000L;
        this.timeout = timeout;
        this.clock = clock;
        this.slow = slow;
        this.onAbandon = onAbandon;
    }

    /**
     * Open the bound for the file at spec index {@code index}, on the calling thread. Close it when
     * that file is done; nothing outside the window is timed.
     */
    Watch watch(int index, File file) {
        Watch w = new Watch(index, file, clock.nanos());
        inFlight.put(index, w);
        return w;
    }

    /** Why the file at {@code index} was abandoned, or null while it is still the run's to finish. */
    @Nullable
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
        // real verdict.
        if (!inFlight.remove(w.index, w)) return;
        verdicts.put(w.index, "timed out after " + human(millis(elapsed)) + " " + timeout.describe());
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
        private final long startNanos;
        private volatile long nextNoticeNanos = warnNanos;

        private Watch(int index, File file, long startNanos) {
            this.index = index;
            this.file = file;
            this.startNanos = startNanos;
        }

        @Override
        public void close() {
            inFlight.remove(index, this);
        }
    }
}
