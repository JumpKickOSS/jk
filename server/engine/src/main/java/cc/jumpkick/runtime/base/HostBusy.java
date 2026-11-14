// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.run.JkThreads;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import org.jspecify.annotations.Nullable;

/**
 * How busy the host's cores are with work that is not this engine's: the share of every core's time
 * spent outside the engine and its child processes over the last few seconds, 0 to 1. A build beside
 * twelve busy loops on 24 cores ran 23 to 40% longer than on an idle host, and a wall replayed from
 * one load priced a run under the other by as much.
 *
 * <p>Read from {@code /proc/stat} and the engine's own CPU time once a second over a window of
 * {@link #WINDOW} samples, on a daemon thread that runs only while a job does and for {@link
 * #KEEP_WARM} after the last job ended or the last read: an idle engine reads nothing. A read with
 * no window, the first after an idle spell, waits until {@link #SHORT_READ} has passed since the
 * sampler woke and reads that. The engine's own time counts every child it has reaped ({@code
 * cutime}, {@code cstime}) and every live descendant's, so a child that lives under a second is
 * still the engine's work. Linux only: where {@code /proc/stat} is missing (macOS, Windows) the load
 * is unknown, {@code -1}, a run records it so ({@link StepLedger#UNKNOWN_BUSY}) and nothing is
 * compensated.
 */
public final class HostBusy {

    /** Samples the share is read over, one a second. */
    static final int WINDOW = 4;

    /** How long sampling goes on after the last job ended or the last read of the load. */
    static final Duration KEEP_WARM = Duration.ofSeconds(30);

    /** The interval a read with no window waits for, from the sampler's first sample. */
    static final Duration SHORT_READ = Duration.ofMillis(250);

    private static final Path STAT = Path.of("/proc/stat");
    private static final Path SELF_STAT = Path.of("/proc/self/stat");

    private static final Object LOCK = new Object();

    /** {nanos, total ticks, busy ticks, own ticks}, oldest first; guarded by {@link #LOCK}. */
    private static final ArrayDeque<long[]> SAMPLES = new ArrayDeque<>();

    /** Whether the sampler thread runs; guarded by {@link #LOCK}. */
    private static boolean running;

    /** The jobs running now, by request id. */
    private static final Set<Long> JOBS = ConcurrentHashMap.newKeySet();

    /** {@link Clock#nanos} until which the sampler keeps going with no job running. */
    private static volatile long warmUntil;

    private static volatile long keepWarmNanos = KEEP_WARM.toNanos();

    /** Samples taken over the engine's life. */
    static final AtomicLong SAMPLED = new AtomicLong();

    /** The engine's own CPU ticks at the last sample: what it reads never goes back. */
    private static long ownTicks;

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
        demand();
    }

    /** A job started: the sampler runs until it ends, and for {@link #KEEP_WARM} after. */
    public static void jobStarted(long requestId) {
        JOBS.add(requestId);
        demand();
    }

    /** The job {@link #jobStarted} named ended; one that never started is ignored. */
    public static void jobEnded(long requestId) {
        if (JOBS.remove(requestId)) demand();
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

    /** Sets how long the sampler outlives the last job and read; {@code null} goes back to {@link #KEEP_WARM}. */
    static void keepWarmForTests(@Nullable Duration keepWarm) {
        keepWarmNanos = (keepWarm == null ? KEEP_WARM : keepWarm).toNanos();
    }

    /** Whether the sampler thread runs now. */
    static boolean sampling() {
        synchronized (LOCK) {
            return running;
        }
    }

    /** The external busy share over the last few seconds, 0 to 1; {@code -1} when unknown. */
    public static double external() {
        return live.getAsDouble();
    }

    private static double sampled() {
        demand();
        long[] first;
        synchronized (LOCK) {
            first = SAMPLES.peekFirst();
            if (first == null) return -1;
            if (SAMPLES.size() < 2) {
                long wait = first[0] + SHORT_READ.toNanos() - Clock.SYSTEM.nanos();
                if (wait > 0) {
                    try {
                        LOCK.wait(Math.max(1, wait / 1_000_000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }
            }
        }
        boolean shortRead;
        synchronized (LOCK) {
            shortRead = SAMPLES.size() < 2;
        }
        if (shortRead) sample();
        synchronized (LOCK) {
            if (SAMPLES.size() < 2) return -1;
            long[] from = SAMPLES.peekFirst();
            long[] to = SAMPLES.peekLast();
            return share(from, to);
        }
    }

    /** The external share between two samples; {@code -1} when no time passed between them. */
    private static double share(long[] from, long[] to) {
        long total = to[1] - from[1];
        if (total <= 0) return -1;
        long busy = to[2] - from[2];
        long own = to[3] - from[3];
        return Math.max(0, Math.min(1, (busy - own) / (double) total));
    }

    /** Starts the sampler unless it runs, and keeps it going for {@link #keepWarmNanos} from now. */
    private static void demand() {
        warmUntil = Clock.SYSTEM.nanos() + keepWarmNanos;
        if (!Files.isReadable(STAT)) return;
        synchronized (LOCK) {
            if (running) return;
            running = true;
            SAMPLES.clear();
        }
        sample();
        JkThreads.platform().execute(HostBusy::loop);
    }

    private static void loop() {
        try {
            while (true) {
                Thread.sleep(Duration.ofSeconds(1));
                synchronized (LOCK) {
                    if (JOBS.isEmpty() && Clock.SYSTEM.nanos() - warmUntil > 0) {
                        running = false;
                        SAMPLES.clear();
                        return;
                    }
                }
                sample();
            }
        } catch (InterruptedException e) {
            synchronized (LOCK) {
                running = false;
                SAMPLES.clear();
            }
            Thread.currentThread().interrupt();
        }
    }

    /** One sample: {nanos, total ticks, busy ticks, own ticks}. */
    private static void sample() {
        long[] ticks = cpuTicks();
        if (ticks == null) return;
        long own = ownCpuTicks();
        long[] s = {Clock.SYSTEM.nanos(), ticks[0], ticks[1], own};
        SAMPLED.incrementAndGet();
        synchronized (LOCK) {
            SAMPLES.addLast(s);
            while (SAMPLES.size() > WINDOW + 1) SAMPLES.removeFirst();
            LOCK.notifyAll();
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

    /**
     * CPU ticks of the engine and everything under it: its own time and its reaped children's
     * ({@code /proc/self/stat}), then each live descendant's, parents before children, each with
     * its own reaped children's. A child that exits between two samples moves its whole time into
     * its parent's reaped total, so none of it is lost. Read in that order a child reaped mid-read
     * is missed once and counted at the next sample, never counted twice; the total never goes back.
     */
    private static synchronized long ownCpuTicks() {
        long self = statTicks(SELF_STAT);
        long sum = Math.max(0, self);
        List<ProcessHandle> tree = new ArrayList<>();
        try (var kids = ProcessHandle.current().descendants()) {
            kids.forEach(tree::add);
        }
        long me = ProcessHandle.current().pid();
        Map<Long, Long> parents = new HashMap<>();
        for (ProcessHandle p : tree)
            parents.put(p.pid(), p.parent().map(ProcessHandle::pid).orElse(me));
        Map<Long, Integer> depth = new HashMap<>();
        for (ProcessHandle p : tree) depth.put(p.pid(), depthOf(p.pid(), me, parents));
        tree.sort(Comparator.comparingInt(p -> depth.getOrDefault(p.pid(), 0)));
        for (ProcessHandle p : tree) {
            sum += Math.max(0, statTicks(Path.of("/proc", Long.toString(p.pid()), "stat")));
        }
        ownTicks = Math.max(ownTicks, sum);
        return ownTicks;
    }

    /** How many parents up {@code pid} is from {@code root}, by {@code parents}. */
    private static int depthOf(long pid, long root, Map<Long, Long> parents) {
        int d = 1;
        for (Long up = parents.get(pid); up != null && up != root && d < 64; up = parents.get(up)) d++;
        return d;
    }

    /**
     * {@code utime + stime + cutime + cstime} of a {@code /proc/<pid>/stat}, in ticks; {@code -1}
     * when it is gone or does not parse.
     */
    static long statTicks(Path stat) {
        try {
            return parseStat(Files.readString(stat, StandardCharsets.US_ASCII));
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /** {@link #statTicks} of one stat line; the command name may hold spaces and parentheses. */
    static long parseStat(String line) {
        String[] f = line.substring(line.lastIndexOf(')') + 2).trim().split(" ");
        // f[0] is field 3 (state); utime, stime, cutime and cstime are fields 14 to 17.
        return Long.parseLong(f[11]) + Long.parseLong(f[12]) + Long.parseLong(f[13]) + Long.parseLong(f[14]);
    }
}
