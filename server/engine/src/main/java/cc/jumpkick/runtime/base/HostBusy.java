// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.Log;
import cc.jumpkick.run.JkThreads;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;
import org.jspecify.annotations.Nullable;

/**
 * How busy the host's cores are with work that is not this engine's: the share of every core's time
 * spent outside the engine and its child processes over the last few seconds, 0 to 1 (JK-6BJTV). A
 * build beside twelve busy loops on 24 cores ran 23 to 40% longer than on an idle host, and a wall
 * replayed from one load priced a run under the other by as much.
 *
 * <p>Read from {@code /proc/stat} and the engine's process tree once a second on a daemon thread,
 * started on first use, over a window of {@link #WINDOW} samples: the one-minute load average lags
 * by a minute and still carries the last build's own threads into the next one's start. Where
 * {@code /proc/stat} is missing (macOS, Windows) the load is unknown, {@code -1}, and nothing is
 * compensated.
 */
public final class HostBusy {

    /** Samples the share is read over, one a second. */
    static final int WINDOW = 4;

    private static final Path STAT = Path.of("/proc/stat");
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final ArrayDeque<long[]> SAMPLES = new ArrayDeque<>();

    /** Nanoseconds of CPU per jiffy of /proc/stat: USER_HZ is 100 on every Linux jk runs on. */
    private static final long NANOS_PER_TICK = 10_000_000L;

    private HostBusy() {}

    /**
     * How a wall goes with the external load: as {@code (1 + load)} to this power. Twelve busy loops
     * on 24 cores (load 0.5) grew the engine's body-edit builds and tests by 23 to 40%, 1.5^0.65 =
     * 1.30 (jk's 2026-11-13 corpus, S4 and S7).
     */
    static final double ELASTICITY = 0.65;

    /**
     * What a wall measured under {@code then} external load is multiplied by for a run under {@code
     * now}: {@code 1} when either is unknown.
     */
    public static double factor(double now, double then) {
        if (now < 0 || then < 0) return 1;
        return Math.pow((1 + Math.min(1, now)) / (1 + Math.min(1, then)), ELASTICITY);
    }

    /** Starts sampling, so the first estimate after an engine start has a window to read. */
    public static void warm() {
        start();
    }

    /**
     * What {@link #external} answers: the sampled host, or what a test pins it to ({@link
     * #forTests}), since a test that prices a build must not read whatever else the host it runs on
     * is doing.
     */
    private static volatile DoubleSupplier live = HostBusy::sampled;

    /** Pins {@link #external} for a test; {@code null} goes back to the sampled host. */
    public static void forTests(@Nullable DoubleSupplier pinned) {
        live = pinned == null ? HostBusy::sampled : pinned;
    }

    /** The external busy share over the last few seconds, 0 to 1; {@code -1} when unknown. */
    public static double external() {
        return live.getAsDouble();
    }

    private static double sampled() {
        start();
        synchronized (SAMPLES) {
            if (SAMPLES.size() < 2) return -1;
            long[] first = SAMPLES.peekFirst();
            long[] last = SAMPLES.peekLast();
            long total = last[0] - first[0];
            if (total <= 0) return -1;
            long busy = last[1] - first[1];
            long own = (last[2] - first[2]) / NANOS_PER_TICK;
            return Math.max(0, Math.min(1, (busy - own) / (double) total));
        }
    }

    private static void start() {
        if (!Files.isReadable(STAT) || !STARTED.compareAndSet(false, true)) return;
        sample();
        JkThreads.platform().execute(() -> {
            try {
                while (true) {
                    Thread.sleep(Duration.ofSeconds(1));
                    sample();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** One sample: {total ticks, busy ticks, own CPU nanos}. */
    private static void sample() {
        long[] ticks = cpuTicks();
        if (ticks == null) return;
        long[] s = {ticks[0], ticks[1], ownCpuNanos()};
        synchronized (SAMPLES) {
            SAMPLES.addLast(s);
            while (SAMPLES.size() > WINDOW + 1) SAMPLES.removeFirst();
        }
    }

    /** {total, busy} jiffies over every core, from the {@code cpu} line; null when unreadable. */
    static long @Nullable [] cpuTicks() {
        try {
            for (String line : Files.readAllLines(STAT, StandardCharsets.US_ASCII)) {
                if (!line.startsWith("cpu ")) continue;
                return parse(line);
            }
        } catch (IOException | RuntimeException e) {
            Log.debug("HostBusy: /proc/stat unreadable", e);
        }
        return null;
    }

    /** {total, busy} of a {@code cpu} line: idle and iowait are not busy; guest time is in user already. */
    static long[] parse(String line) {
        String[] f = line.trim().split("\\s+");
        long total = 0;
        long idle = 0;
        // user nice system idle iowait irq softirq steal [guest guest_nice]
        for (int i = 1; i < f.length && i <= 8; i++) {
            long v = Long.parseLong(f[i]);
            total += v;
            if (i == 4 || i == 5) idle += v;
        }
        return new long[] {total, total - idle};
    }

    /** Each live process of the tree's CPU time at the last sample, by pid. */
    private static final Map<Long, Long> LAST_CPU = new HashMap<>();

    /** The tree's CPU time summed from each process's growth between samples. */
    private static long ownCumulative;

    /**
     * CPU time of the engine and every process under it, as a running sum of each process's growth
     * since the last sample: a test JVM that exits takes its time out of the tree, and read as a sum
     * of the tree its exit would show as a burst of someone else's work.
     */
    private static synchronized long ownCpuNanos() {
        Map<Long, Long> now = new HashMap<>();
        ProcessHandle self = ProcessHandle.current();
        now.put(self.pid(), cpu(self));
        try (var kids = self.descendants()) {
            kids.forEach(k -> now.put(k.pid(), cpu(k)));
        }
        for (Map.Entry<Long, Long> e : now.entrySet()) {
            long before = LAST_CPU.getOrDefault(e.getKey(), 0L);
            ownCumulative += Math.max(0, e.getValue() - before);
        }
        LAST_CPU.clear();
        LAST_CPU.putAll(now);
        return ownCumulative;
    }

    private static long cpu(ProcessHandle p) {
        return p.info().totalCpuDuration().map(Duration::toNanos).orElse(0L);
    }
}
