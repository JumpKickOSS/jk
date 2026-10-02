// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.AvailableCpus;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.HostProcessors;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskNames;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Byte leases for every process {@link JobWorkers} forks. Capacity is {@link
 * WorkerContainment#budgetBytes()}, the same number written to the workers cgroup {@code
 * memory.max}. A JVM leases its {@code -Xmx} plus overhead {@code max(160 MiB, 12% of -Xmx)};
 * anything else leases {@value #TOOL_MIB} MiB.
 *
 * <p>The overhead is resident memory above the Java heap. Filling a Temurin 25 heap (Parallel GC,
 * 256 MiB metaspace cap, 512 KiB stacks) measured RSS above the committed heap of 22 MiB at
 * {@code -Xmx128m}, 51 MiB at 512m, 85 MiB at 1g and 136 MiB at 2g — about 6% of the heap plus a
 * small base, matching the GC and thread structures native memory tracking had committed. A
 * compiler or test worker also holds metaspace and a code cache that empty probe did not; 160 MiB
 * covers that, and 12% covers the measured GC fraction with margin. A lease bigger than the whole
 * budget still runs, alone: a heap jk planned is lowered so it fits, and a heap the user pinned
 * keeps its flags and leases the whole budget. A lease that does not fit yet waits, first in line
 * first. On Linux it may instead be granted past the budget while {@link OverbookSignals} say the
 * host has room; {@code CI} and {@code JK_OVERBOOK=0} turn that off. A running fork is never killed
 * to make the room.
 *
 * <p>The lease is an estimate. Every {@link WorkerRss#SAMPLE_EVERY} the engine ledger reads each live
 * worker's resident set and charges the larger of the two, so a worker whose native memory
 * (metaspace, thread stacks, direct buffers) outgrows its lease holds back new grants by what it
 * really uses, and is named in the run's warnings, {@code jk engine status} and the engine log.
 *
 * <p>A {@linkplain Resident resident} helper holds its lease between requests. When the head of the
 * queue would fit once residents let go, the ledger asks them to exit; they finish the request in
 * flight, exit, and start again on their next use. So the head waits only on forks that are doing
 * work, never on an idle holder.
 *
 * <p>The queue and the counters live on a {@link Ledger}. {@link #engine()} is the one this process
 * uses: its capacity supplier is {@link WorkerContainment#budgetBytes()}, so {@code
 * JK_WORKER_BUDGET_MB} applies to that instance, its CPU cap is the host's core count, a waiting
 * lease is abandoned once {@link JobWorkers#ended} says the request was shut down, and a lease past
 * the budget is granted only when {@link OverbookSignals#live()} says the host has room.
 */
public final class WorkerLeases {

    /** One mebibyte. */
    public static final long MIB = 1L << 20;

    private static final long GIB = 1L << 30;

    /**
     * Resident cost above {@code -Xmx} that does not shrink with a small heap: metaspace, code
     * cache and thread stacks of a real compiler or test worker.
     */
    public static final long OVERHEAD_FLOOR_BYTES = 160 * MIB;

    /**
     * Fraction of {@code -Xmx} reserved for GC structures. Measured RSS above a filled heap was
     * about 6%; 12% leaves margin as the heap grows.
     */
    public static final double OVERHEAD_FRACTION = 0.12;

    /** Fixed lease for a process that is not a JVM. */
    public static final long TOOL_BYTES = 64 * MIB;

    /** {@link #TOOL_BYTES} in whole mebibytes, for the class note. */
    public static final long TOOL_MIB = TOOL_BYTES / MIB;

    /** {@code -Xmx} assumed for a {@code java} command that names none. */
    static final long UNSIZED_JVM_XMX = 512 * MIB;

    /** Smallest heap a clamped worker is given when the budget can hold it. */
    static final long MIN_XMX = 32 * MIB;

    private static final long REPORT_EVERY_NANOS = 2_000_000_000L;

    /** Holders {@link Snapshot#waiting()} names before it counts the rest. */
    private static final int HOLDERS_SHOWN = 3;

    /** A finished wait shorter than this is recorded and not printed. */
    private static final long OUTPUT_AFTER_NANOS = 500_000_000L;

    /**
     * Reserved bytes, including the lease being granted, stay at or under this many times the
     * budget. The cap is what stops a burst of grants on one cached sample.
     */
    public static final int OVERBOOK_CAP_FACTOR = 2;

    private static final Pattern XMX = Pattern.compile("^(?<prefix>-J)?-Xmx(?<size>\\d+[kKmMgGtT]?)$");
    private static final Pattern XMS = Pattern.compile("^(?<prefix>-J)?-Xms(?<size>\\d+[kKmMgGtT]?)$");
    private static final Pattern XX_HEAP = Pattern.compile(
            "^(?<prefix>-J)?-XX:(?<name>MaxHeapSize|MinHeapSize|InitialHeapSize|SoftMaxHeapSize)=(?<size>\\d+[kKmMgGtT]?)$");

    /**
     * This process's ledger. Capacity is re-read from {@link WorkerContainment#budgetBytes()} at
     * most every two seconds; that read honors {@code JK_WORKER_BUDGET_MB}.
     */
    private static final Ledger ENGINE = new Ledger(
                    new ProcessBudget(),
                    WorkerLeases::forkCpuCap,
                    JobWorkers::ended,
                    OverbookSignals.live(),
                    MemoryProbe::rssBytes)
            .sampleEvery(WorkerRss.SAMPLE_EVERY);

    private WorkerLeases() {}

    /** The ledger {@link JobWorkers}, the compiler host, and {@code jk engine status} share. */
    public static Ledger engine() {
        return ENGINE;
    }

    /**
     * How many forked JVMs may run at once: the host's processors, narrowed to a finite cgroup CPU
     * quota. {@link Runtime#availableProcessors()} is the wrong reading. An engine in a JVM started
     * with {@code -XX:ActiveProcessorCount} (a batch worker's share, or a user's {@code jvm-args})
     * would read that pin, and with no quota {@link AvailableCpus#count()} reports it. A cap of one lets a resident script host or
     * compiler hold the only slot until its idle timeout, and the next fork waits out that timeout.
     */
    static int forkCpuCap() {
        return forkCpuCap(HostProcessors.count(), AvailableCpus.quota());
    }

    /** {@link #forkCpuCap()} for a known host size and quota. {@code quota <= 0} means unlimited. */
    static int forkCpuCap(int hostProcessors, int cgroupQuota) {
        int host = Math.max(1, hostProcessors);
        if (cgroupQuota > 0) return Math.min(host, cgroupQuota);
        return host;
    }

    /** Resident bytes above {@code xmxBytes} that the lease reserves. */
    public static long overheadBytes(long xmxBytes) {
        long scaled = (long) (Math.max(0, xmxBytes) * OVERHEAD_FRACTION);
        return Math.max(OVERHEAD_FLOOR_BYTES, scaled);
    }

    /** Lease of a JVM whose heap ceiling is {@code xmxBytes}. */
    public static long jvmLease(long xmxBytes) {
        return Math.max(0, xmxBytes) + overheadBytes(xmxBytes);
    }

    /**
     * Largest {@code -Xmx}, not above {@code requested}, whose {@linkplain #jvmLease lease} fits in
     * {@code capacity}. When even the overhead floor does not fit, the result is whatever heap the
     * capacity can still launch; the caller leases at most {@code capacity}.
     */
    public static long clampXmx(long requested, long capacity) {
        long ask = Math.max(0, requested);
        if (capacity <= 0) return Math.min(ask == 0 ? MIN_XMX : ask, MIN_XMX);
        if (ask > 0 && jvmLease(ask) <= capacity) return ask;
        long byFraction = (long) (capacity / (1.0 + OVERHEAD_FRACTION));
        long byFloor = capacity - OVERHEAD_FLOOR_BYTES;
        long fit;
        if (byFloor > 0 && overheadBytes(byFloor) == OVERHEAD_FLOOR_BYTES) fit = byFloor;
        else fit = byFraction;
        fit = (Math.max(0, fit) / MIB) * MIB;
        if (fit < MIN_XMX || jvmLease(fit) > capacity) {
            long shaved = (Math.max(MIB, capacity) / MIB) * MIB;
            while (shaved > MIB && jvmLease(shaved) > capacity) shaved -= MIB;
            fit = shaved;
        }
        if (ask > 0) fit = Math.min(ask, fit);
        return Math.max(MIB, fit);
    }

    /**
     * Headroom that must remain after a new lease's worst case. The larger of 1 GiB and 10% of
     * {@code budgetBytes}.
     */
    public static long headroomBytes(long budgetBytes) {
        long tenth = budgetBytes > 0 ? budgetBytes / 10 : 0;
        return Math.max(GIB, tenth);
    }

    /** {@code waited 3s for memory}, or {@code waited 1.5s for memory}. */
    public static String waitedPhrase(long nanos) {
        double seconds = Math.max(0, nanos) / 1_000_000_000.0;
        String text = seconds >= 10
                ? String.format(Locale.ROOT, "%.0f", seconds)
                : String.format(Locale.ROOT, "%.1f", seconds);
        if (text.endsWith(".0")) text = text.substring(0, text.length() - 2);
        return "waited " + text + "s for memory";
    }

    /** {@code 512 MiB} or {@code 1.5 GiB}. */
    public static String format(long bytes) {
        if (bytes >= GIB) {
            return String.format(Locale.ROOT, "%.1f GiB", bytes / (double) GIB);
        }
        return Math.max(0, bytes / MIB) + " MiB";
    }

    /**
     * The line a waiting fork shows: {@code waiting for memory: need 512 MiB, free 128 MiB}.
     */
    public static String waitingLine(long needBytes, long freeBytes) {
        return "waiting for memory: need " + format(needBytes) + ", free " + format(Math.max(0, freeBytes));
    }

    /**
     * Command line with {@code -Xmx} / {@code -Xms} / soft-max brought down to {@code xmxBytes}
     * where they were higher. A JVM command that named no heap gains an {@code -Xmx} after its
     * executable; a native-image command a {@code -J-Xmx}, the builder's.
     */
    public static List<String> rewriteHeap(List<String> command, long xmxBytes) {
        long xmx = Math.max(MIB, xmxBytes);
        List<String> out = new ArrayList<>(command.size() + 1);
        boolean sawXmx = false;
        for (String arg : command) {
            Matcher max = XMX.matcher(arg);
            Matcher xx = XX_HEAP.matcher(arg);
            Matcher min = XMS.matcher(arg);
            if (max.matches()) {
                sawXmx = true;
                out.add(prefix(max) + "-Xmx" + mib(xmx) + "m");
            } else if (xx.matches() && "MaxHeapSize".equals(xx.group("name"))) {
                sawXmx = true;
                out.add(prefix(xx) + "-XX:MaxHeapSize=" + mib(xmx) + "m");
            } else if (min.matches()) {
                long size = parseSize(min.group("size"));
                out.add(size > xmx ? prefix(min) + "-Xms" + mib(xmx) + "m" : arg);
            } else if (xx.matches()) {
                long size = parseSize(xx.group("size"));
                String name = xx.group("name");
                if (size > xmx
                        && ("MinHeapSize".equals(name)
                                || "InitialHeapSize".equals(name)
                                || "SoftMaxHeapSize".equals(name))) {
                    out.add(prefix(xx) + "-XX:" + name + "=" + mib(xmx) + "m");
                } else out.add(arg);
            } else out.add(arg);
        }
        if (!sawXmx && !out.isEmpty()) {
            int at = command.size() > 1 && setsid(command.get(0)) ? 2 : 1;
            out.add(Math.min(at, out.size()), (nativeImageCommand(command) ? "-J" : "") + "-Xmx" + mib(xmx) + "m");
        }
        return List.copyOf(out);
    }

    /** The {@code -Xmx} the command asks for, or {@code -1} when it names none. The last flag wins. */
    public static long parseXmx(List<String> command) {
        long found = -1;
        for (String arg : command) {
            Matcher max = XMX.matcher(arg);
            if (max.matches()) {
                long size = parseSize(max.group("size"));
                if (size > 0) found = size;
                continue;
            }
            Matcher xx = XX_HEAP.matcher(arg);
            if (xx.matches() && "MaxHeapSize".equals(xx.group("name"))) {
                long size = parseSize(xx.group("size"));
                if (size > 0) found = size;
            }
        }
        return found;
    }

    static boolean jvmExecutable(String arg) {
        String name = arg;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe")) name = name.substring(0, name.length() - 4);
        return name.equals("java") || name.equals("javaw") || name.equals(TaskNames.NATIVE_IMAGE);
    }

    /** A HotSpot size token ({@code 512m}, {@code 1g}); {@code -1} when it is not one. */
    static long parseSize(String token) {
        if (token == null || token.isEmpty()) return -1;
        char last = token.charAt(token.length() - 1);
        long mul = 1;
        String digits = token;
        if (last == 'k' || last == 'K') {
            mul = 1024;
            digits = token.substring(0, token.length() - 1);
        } else if (last == 'm' || last == 'M') {
            mul = MIB;
            digits = token.substring(0, token.length() - 1);
        } else if (last == 'g' || last == 'G') {
            mul = GIB;
            digits = token.substring(0, token.length() - 1);
        } else if (last == 't' || last == 'T') {
            mul = GIB * 1024;
            digits = token.substring(0, token.length() - 1);
        }
        try {
            return Long.parseLong(digits) * mul;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The program {@code command} execs, looking past a leading {@code setsid}. */
    static String executable(List<String> command) {
        if (command.isEmpty()) return "";
        if (command.size() > 1 && setsid(command.get(0))) return command.get(1);
        return command.get(0);
    }

    private static boolean setsid(String arg) {
        int slash = Math.max(arg.lastIndexOf('/'), arg.lastIndexOf('\\'));
        String name = slash >= 0 ? arg.substring(slash + 1) : arg;
        return name.equals("setsid");
    }

    static boolean jvmCommand(List<String> command) {
        return !command.isEmpty() && jvmExecutable(executable(command));
    }

    /**
     * The heap a JVM command that names none is leased: a native-image builder's {@link
     * NativeHeap#generous} heap, else {@link #UNSIZED_JVM_XMX}.
     */
    static long defaultXmx(List<String> command, long capacityBytes) {
        return nativeImageCommand(command) ? NativeHeap.generous(capacityBytes) : UNSIZED_JVM_XMX;
    }

    /** Whether {@code command} runs the GraalVM {@code native-image} driver. */
    public static boolean nativeImageCommand(List<String> command) {
        String exe = executable(command);
        return jvmExecutable(exe) && exe.toLowerCase(Locale.ROOT).contains(TaskNames.NATIVE_IMAGE);
    }

    private static String prefix(Matcher matcher) {
        return matcher.group("prefix") == null ? "" : "-J";
    }

    private static long mib(long bytes) {
        return Math.max(1, bytes / MIB);
    }

    /**
     * What {@code jk engine status} prints. {@code leasedBytes} charges each worker the larger of its
     * lease and its last measured resident set. {@code overbookedBytes} is the part of {@code
     * leasedBytes} above {@code budgetBytes}, or {@code 0} when the charged total fits. {@code
     * waiting} names what the head of the queue needs and who holds the memory; {@code ""} when
     * nothing is queued. {@code overLease} names the workers {@linkplain WorkerRss#farOverLease far over
     * their lease}, largest first; {@code ""} when none is.
     */
    public record Snapshot(
            long budgetBytes,
            long leasedBytes,
            long overbookedBytes,
            int queued,
            int runningJvms,
            int cpuCap,
            String waiting,
            String overLease) {}

    /**
     * A helper that stays resident between requests (the build-script host). {@code yield} is called
     * off the ledger's lock when the head of the queue needs this lease back; it lets the request in
     * flight finish, then makes the process exit, which closes the grant. It may block.
     */
    public record Resident(String name, Runnable yield) {
        public Resident {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(yield, "yield");
        }
    }

    /**
     * One queue of waiters and the bytes and JVM slots currently handed out. {@code capacityBytes}
     * and {@code cpuCap} are read when a lease is granted, so a supplier may change what fits
     * without replacing the ledger.
     *
     * <p>A granted worker whose pid is {@linkplain Grant#watch known} is charged the larger of its
     * lease and the resident set {@link #sample} last read, so a worker that outgrows its lease
     * holds back new grants by what it really uses. While any worker is {@linkplain WorkerRss#farOverLease
     * far over its lease}, nothing is overbooked: the reservation no longer bounds what running
     * forks may use.
     */
    public static final class Ledger {
        private final LongSupplier capacity;
        private final IntSupplier cpus;
        private final LongPredicate ended;
        private final OverbookSignals.Source signals;
        private final WorkerRss.Source rss;
        private final Object lock = new Object();
        private final ArrayDeque<Waiter> queue = new ArrayDeque<>();
        private final LinkedHashSet<Waiter> held = new LinkedHashSet<>();
        private final ConcurrentHashMap<Long, AtomicLong> waited = new ConcurrentHashMap<>();
        private final Set<TaskContext> warnedSteps = Collections.newSetFromMap(new WeakHashMap<>());
        private long leased;
        /** Sum over held workers of the resident bytes above their lease. */
        private long excess;
        /** Held workers {@linkplain WorkerRss#farOverLease far over their lease}. */
        private int overLeaseCount;

        private int jvmRunning;
        private @Nullable Duration sampleEvery;
        private boolean sampling;

        /**
         * {@code ended} is true once that request has been shut down. A lease waiting for it is
         * abandoned instead of staying queued. The engine ledger passes {@link JobWorkers#ended}.
         * Overbooking is off: a lease that does not fit the reservation waits.
         */
        public Ledger(LongSupplier capacityBytes, IntSupplier cpuCap, LongPredicate ended) {
            this(capacityBytes, cpuCap, ended, OverbookSignals.off());
        }

        /**
         * As {@link #Ledger(LongSupplier, IntSupplier, LongPredicate)} with {@code signals} consulted
         * when a lease does not fit the reservation. The source is read under this ledger's lock, so
         * a cached source is what keeps that off the syscall path. No resident set is read.
         */
        public Ledger(
                LongSupplier capacityBytes, IntSupplier cpuCap, LongPredicate ended, OverbookSignals.Source signals) {
            this(capacityBytes, cpuCap, ended, signals, pid -> -1);
        }

        /** As the four-argument form, reading each watched worker's resident set from {@code rss}. */
        public Ledger(
                LongSupplier capacityBytes,
                IntSupplier cpuCap,
                LongPredicate ended,
                OverbookSignals.Source signals,
                WorkerRss.Source rss) {
            this.capacity = capacityBytes;
            this.cpus = cpuCap;
            this.ended = ended;
            this.signals = Objects.requireNonNull(signals, "signals");
            this.rss = Objects.requireNonNull(rss, "rss");
        }

        /**
         * Run {@link #sample} every {@code every} on a daemon thread, started by the first {@link
         * Grant#watch}. Each pass also lets {@link WorkerContainment#sweep} settle exited workers'
         * cgroups.
         */
        Ledger sampleEvery(Duration every) {
            synchronized (lock) {
                this.sampleEvery = every;
            }
            return this;
        }

        /** Bytes charged against the budget: each held lease, or its worker's resident set when larger. */
        private long charged() {
            return leased + excess;
        }

        /** Bytes this ledger will hand out. */
        public long capacityBytes() {
            return Math.max(0, capacity.getAsLong());
        }

        /** How many forked JVMs may run at once. */
        public int cpuCap() {
            return Math.max(1, cpus.getAsInt());
        }

        public Snapshot snapshot() {
            synchronized (lock) {
                long budget = capacityBytes();
                long charged = charged();
                long over = charged > budget ? charged - budget : 0;
                return new Snapshot(
                        budget,
                        charged,
                        over,
                        queue.size(),
                        jvmRunning,
                        cpuCap(),
                        waitingLine(budget),
                        overLeaseLine());
            }
        }

        /** Leases waiting for capacity. */
        public int queued() {
            synchronized (lock) {
                return queue.size();
            }
        }

        /** Nanoseconds request {@code requestId} has spent waiting for a lease. */
        public long waitedNanos(long requestId) {
            AtomicLong n = waited.get(requestId);
            return n == null ? 0 : n.get();
        }

        /**
         * Block until {@code command}'s lease fits, classifying the heap with {@link
         * JvmOptions.HeapChoice#inspect}. A planned JVM lease larger than the budget is clamped
         * first; a user pin is not. Interrupt, or {@link JobWorkers#ended} for {@code requestId},
         * abandons the wait.
         */
        public Grant acquire(List<String> command, @Nullable Long requestId) throws InterruptedException {
            return acquire(command, requestId, JvmOptions.HeapChoice.inspect(command));
        }

        /**
         * As {@link #acquire(List, Long)} with a choice measured before the command was shortened
         * into an argfile. {@code choice}'s {@code -Xmx} stands when the shortened command no longer
         * shows one.
         */
        public Grant acquire(List<String> command, @Nullable Long requestId, JvmOptions.HeapChoice choice)
                throws InterruptedException {
            return acquire(demand(command, choice), requestId);
        }

        /**
         * As {@link #acquire(List, Long, JvmOptions.HeapChoice)} for a helper that stays resident
         * between requests: it leases its memory but takes no CPU slot, so an idle helper never holds
         * back a fork that has work to run, and {@code resident} is asked to give the memory back
         * when the head of the queue needs it.
         */
        public Grant acquireResident(List<String> command, JvmOptions.HeapChoice choice, Resident resident)
                throws InterruptedException {
            return acquire(
                    new Waiter(demand(command, choice).resident(), null, Objects.requireNonNull(resident, "resident")));
        }

        /** As {@link #acquire(List, Long)} for a lease already measured in bytes. */
        public Grant acquireBytes(long bytes, boolean jvm, @Nullable Long requestId) throws InterruptedException {
            long cap = capacityBytes();
            long leasedBytes = Math.min(Math.max(1, bytes), Math.max(1, cap));
            boolean clamped = leasedBytes < bytes;
            return acquire(new Demand(leasedBytes, 0, jvm, clamped, false, false), requestId);
        }

        /**
         * Drop every queued lease for {@code requestId}. Granted leases stay; the process kill path
         * releases those when the process exits.
         */
        public void cancelRequest(long requestId) {
            synchronized (lock) {
                for (Waiter waiter : queue) {
                    if (waiter.requestId != null && waiter.requestId == requestId) waiter.cancelled = true;
                }
                lock.notifyAll();
            }
        }

        private Grant acquire(Demand demand, @Nullable Long requestId) throws InterruptedException {
            return acquire(new Waiter(demand, requestId, null));
        }

        private Grant acquire(Waiter waiter) throws InterruptedException {
            @Nullable Long requestId = waiter.requestId;
            long arrived = Clock.SYSTEM.nanos();
            boolean queued = false;
            synchronized (lock) {
                if (grantIfHead(waiter)) return finish(waiter, 0, false);
                queue.addLast(waiter);
                queued = true;
            }
            if (queued) {
                long free;
                synchronized (lock) {
                    free = Math.max(0, capacityBytes() - charged());
                }
                reportWaiting(waiter.demand.bytes, free);
            }
            long lastReport = Clock.SYSTEM.nanos();
            try {
                while (true) {
                    boolean granted;
                    long need;
                    long free;
                    synchronized (lock) {
                        if (waiter.cancelled || requestEnded(requestId)) {
                            queue.remove(waiter);
                            grantQueued();
                            lock.notifyAll();
                            throw new InterruptedException("cancelled while waiting for memory");
                        }
                        grantQueued();
                        granted = waiter.granted;
                        need = waiter.demand.bytes;
                        free = Math.max(0, capacityBytes() - charged());
                        if (!granted) lock.wait(250);
                    }
                    if (granted) break;
                    long now = Clock.SYSTEM.nanos();
                    if (lastReport == 0 || now - lastReport >= REPORT_EVERY_NANOS) {
                        reportWaiting(need, free);
                        lastReport = now;
                    }
                }
            } catch (InterruptedException e) {
                synchronized (lock) {
                    queue.remove(waiter);
                    if (waiter.granted) release(waiter);
                    grantQueued();
                    lock.notifyAll();
                }
                throw e;
            }
            long waitedNanos = Clock.SYSTEM.nanos() - arrived;
            return finish(waiter, waitedNanos, queued);
        }

        private Grant finish(Waiter waiter, long waitedNanos, boolean queued) {
            if (waiter.requestId != null && waitedNanos > 0) {
                waited.computeIfAbsent(waiter.requestId, id -> new AtomicLong()).addAndGet(waitedNanos);
            }
            if (queued) noteGranted(waiter, waitedNanos);
            return new Grant(this, waiter);
        }

        /** Under the lock: grant {@code candidate} when it is the only waiter and it fits. */
        private boolean grantIfHead(Waiter candidate) {
            if (!queue.isEmpty() || !admit(candidate)) return false;
            take(candidate);
            return true;
        }

        /** Under the lock: grant the front of the queue while it fits. */
        private void grantQueued() {
            while (!queue.isEmpty()) {
                Waiter head = queue.peekFirst();
                if (head.cancelled) {
                    queue.pollFirst();
                    continue;
                }
                if (!admit(head)) {
                    reclaimFor(head);
                    return;
                }
                queue.pollFirst();
                take(head);
            }
        }

        /**
         * Under the lock: when {@code head} is short only of memory that residents hold, ask enough of
         * them to exit. Each resident is asked once; its grant closes when its process is gone. While
         * forks that are working still hold what the head needs, residents are left alone.
         */
        private void reclaimFor(Waiter head) {
            if (head.demand.cpuSlot && jvmRunning >= cpuCap()) return;
            long cap = capacityBytes();
            long resident = 0;
            long yielding = 0;
            for (Waiter w : held) {
                if (w.resident == null) continue;
                resident += w.demand.bytes;
                if (w.yieldAsked) yielding += w.demand.bytes;
            }
            long charged = charged();
            if (resident == 0 || head.demand.bytes > cap - (charged - resident)) return;
            List<Waiter> ask = new ArrayList<>();
            for (Waiter w : held) {
                if (head.demand.bytes <= cap - (charged - yielding)) break;
                if (w.resident == null || w.yieldAsked) continue;
                w.yieldAsked = true;
                yielding += w.demand.bytes;
                ask.add(w);
            }
            for (Waiter w : ask) {
                Resident r = Objects.requireNonNull(w.resident);
                Log.info("jk engine: asking the " + r.name() + " to exit: " + ownerOf(head) + " needs "
                        + format(head.demand.bytes) + " and the " + r.name() + " holds " + format(w.demand.bytes));
                SessionContext.startVirtual("jk-lease-yield", () -> {
                    try {
                        r.yield().run();
                    } catch (RuntimeException e) {
                        Log.warn("jk engine: the " + r.name() + " did not exit when asked: " + e.getMessage());
                    }
                });
            }
        }

        /**
         * Under the lock: {@code job #7 needs 13.2 GiB (12.9 GiB free); held by job #3 6.1 GiB in 9
         * forks, build-script host 608 MiB (asked to exit)}. Empty when nothing is queued.
         */
        private String waitingLine(long budget) {
            Waiter head = null;
            for (Waiter w : queue) {
                if (!w.cancelled) {
                    head = w;
                    break;
                }
            }
            if (head == null) return "";
            StringBuilder line = new StringBuilder(ownerOf(head)).append(" needs ");
            if (head.demand.cpuSlot && jvmRunning >= cpuCap()) {
                line.append("a JVM slot (")
                        .append(jvmRunning)
                        .append('/')
                        .append(cpuCap())
                        .append(" running)");
            } else {
                line.append(format(head.demand.bytes))
                        .append(" (")
                        .append(format(Math.max(0, budget - charged())))
                        .append(" free)");
            }
            Map<String, Holder> holders = new LinkedHashMap<>();
            for (Waiter w : held) {
                holders.computeIfAbsent(ownerOf(w), Holder::new).add(w);
            }
            if (holders.isEmpty()) return line.toString();
            List<Holder> ranked = new ArrayList<>(holders.values());
            ranked.sort((a, b) -> Long.compare(b.bytes, a.bytes));
            line.append("; held by ");
            int shown = Math.min(HOLDERS_SHOWN, ranked.size());
            for (int i = 0; i < shown; i++) {
                if (i > 0) line.append(", ");
                Holder h = ranked.get(i);
                line.append(h.owner).append(' ').append(format(h.bytes));
                if (h.forks > 1) line.append(" in ").append(h.forks).append(" forks");
                if (h.asked) line.append(" (asked to exit)");
            }
            if (ranked.size() > shown)
                line.append(", ").append(ranked.size() - shown).append(" more");
            return line.toString();
        }

        /**
         * Under the lock. A JVM still needs a free core. Memory fits the reservation, or — when this
         * ledger has signals — the host sample covers the new lease's worst case and the reserved
         * total stays inside {@link #OVERBOOK_CAP_FACTOR} times the budget. Existing forks are not
         * re-charged here: the sample's free bytes are their real usage.
         */
        private boolean admit(Waiter waiter) {
            if (waiter.demand.cpuSlot && jvmRunning >= cpuCap()) return false;
            long cap = capacityBytes();
            long charged = charged();
            if (charged <= cap && waiter.demand.bytes <= cap - charged) {
                waiter.overbooked = false;
                return true;
            }
            return overbook(waiter, cap);
        }

        private boolean overbook(Waiter waiter, long cap) {
            if (cap <= 0) return false;
            long bytes = waiter.demand.bytes;
            long ceiling =
                    cap > Long.MAX_VALUE / OVERBOOK_CAP_FACTOR ? Long.MAX_VALUE : cap * (long) OVERBOOK_CAP_FACTOR;
            if (overLeaseCount > 0) return false;
            long charged = charged();
            if (bytes > ceiling || charged > ceiling - bytes) return false;
            OverbookSignals.Reading reading = readSignals();
            if (!reading.enabled() || !reading.pressureLow()) return false;
            if (!reading.covers(bytes, headroomBytes(cap))) return false;
            waiter.overbooked = true;
            waiter.sampleFree = reading.freeBytes();
            waiter.sampleSome = reading.someAvg10();
            waiter.sampleFull = reading.fullAvg10();
            return true;
        }

        private OverbookSignals.Reading readSignals() {
            try {
                return signals.read();
            } catch (RuntimeException e) {
                Log.debug("overbook signals: unreadable", e);
                return OverbookSignals.Reading.closed();
            }
        }

        private void take(Waiter waiter) {
            held.add(waiter);
            leased += waiter.demand.bytes;
            if (waiter.demand.cpuSlot) jvmRunning++;
            waiter.granted = true;
            if (waiter.overbooked) logOverbook(waiter);
        }

        private void logOverbook(Waiter waiter) {
            long cap = capacityBytes();
            Log.info("jk engine: "
                    + overbookedLine(
                            waiter.demand.bytes,
                            charged(),
                            cap,
                            new OverbookSignals.Reading(
                                    true, waiter.sampleFree, waiter.sampleSome, waiter.sampleFull)));
        }

        private void release(Waiter waiter) {
            if (waiter.released) return;
            waiter.released = true;
            held.remove(waiter);
            leased -= waiter.demand.bytes;
            if (leased < 0) leased = 0;
            excess = Math.max(0, excess - waiter.excess());
            if (waiter.overLease) overLeaseCount = Math.max(0, overLeaseCount - 1);
            if (waiter.demand.cpuSlot) jvmRunning = Math.max(0, jvmRunning - 1);
            if (waiter.named) {
                Log.info("jk engine: " + waiter.label() + " exited; resident set peaked at " + format(waiter.peakRss)
                        + ", leased " + format(waiter.demand.bytes));
            }
        }

        /**
         * Read every watched worker's resident set once and charge it. A worker that first goes
         * {@linkplain WorkerRss#farOverLease far over} its lease is named: in the engine log, and as a run
         * warning on the step that forked it, once per step. Reads happen off the lock.
         */
        public void sample() {
            List<Waiter> watched = new ArrayList<>();
            synchronized (lock) {
                for (Waiter w : held) if (w.pid > 0) watched.add(w);
            }
            if (watched.isEmpty()) return;
            long[] readings = new long[watched.size()];
            for (int i = 0; i < readings.length; i++) {
                try {
                    readings[i] = rss.rssBytes(watched.get(i).pid);
                } catch (RuntimeException e) {
                    readings[i] = -1;
                }
            }
            List<Waiter> crossed = new ArrayList<>();
            List<TaskContext> warnOn = new ArrayList<>();
            synchronized (lock) {
                for (int i = 0; i < readings.length; i++) {
                    Waiter w = watched.get(i);
                    if (w.released || readings[i] < 0) continue;
                    excess -= w.excess();
                    w.rss = readings[i];
                    w.peakRss = Math.max(w.peakRss, w.rss);
                    excess += w.excess();
                    boolean over = WorkerRss.farOverLease(w.demand.bytes, w.rss);
                    if (over != w.overLease) overLeaseCount += over ? 1 : -1;
                    w.overLease = over;
                    if (over && !w.named) {
                        w.named = true;
                        crossed.add(w);
                        TaskContext step = w.step;
                        warnOn.add(step != null && warnedSteps.add(step) ? step : null);
                    }
                }
                grantQueued();
                lock.notifyAll();
            }
            for (int i = 0; i < crossed.size(); i++) {
                Waiter w = crossed.get(i);
                String phrase = WorkerRss.overLeasePhrase(w.what, w.rss, w.demand.bytes);
                Log.warn("jk engine: " + WorkerRss.overLeasePhrase(w.label(), w.rss, w.demand.bytes)
                        + "; charged at its resident set, nothing overbooked while it holds that");
                TaskContext step = warnOn.get(i);
                if (step != null) step.warn(WorkerRss.OVER_LEASE_CODE, phrase);
            }
        }

        /**
         * Under the lock: {@code test JVM pid 4242 (job #7) using 12.2 GiB, leased 700 MiB} for up
         * to three workers far over their lease, largest first. Empty when none is.
         */
        private String overLeaseLine() {
            if (overLeaseCount == 0) return "";
            List<Waiter> over = new ArrayList<>();
            for (Waiter w : held) if (w.overLease) over.add(w);
            over.sort((a, b) -> Long.compare(b.rss, a.rss));
            StringBuilder line = new StringBuilder();
            int shown = Math.min(HOLDERS_SHOWN, over.size());
            for (int i = 0; i < shown; i++) {
                Waiter w = over.get(i);
                if (i > 0) line.append("; ");
                line.append(WorkerRss.overLeasePhrase(w.label(), w.rss, w.demand.bytes));
            }
            if (over.size() > shown)
                line.append("; ").append(over.size() - shown).append(" more");
            return line.toString();
        }

        private void watch(Waiter waiter, long pid, String what, @Nullable TaskContext step) {
            Duration every = null;
            synchronized (lock) {
                if (waiter.released) return;
                waiter.pid = pid;
                waiter.what = what;
                waiter.step = step;
                if (sampleEvery != null && !sampling) {
                    sampling = true;
                    every = sampleEvery;
                }
            }
            if (every != null) startSampler(every);
        }

        private void startSampler(Duration every) {
            WorkerRss.start(every, () -> {
                sample();
                WorkerContainment.sweep();
            });
        }

        private void close(Waiter waiter) {
            synchronized (lock) {
                release(waiter);
                grantQueued();
                lock.notifyAll();
            }
        }

        private boolean requestEnded(@Nullable Long requestId) {
            return requestId != null && ended.test(requestId);
        }

        private Demand demand(List<String> command, JvmOptions.HeapChoice choice) {
            long cap = Math.max(1, capacityBytes());
            long requested = parseXmx(command);
            if (requested <= 0 && choice.xmxBytes() > 0) requested = choice.xmxBytes();
            boolean jvm = requested > 0 || jvmCommand(command);
            if (!jvm) {
                long bytes = Math.min(TOOL_BYTES, cap);
                return new Demand(bytes, 0, false, bytes < TOOL_BYTES, false, false);
            }
            long xmx = requested > 0 ? requested : defaultXmx(command, cap);
            if (choice.userPinned()) {
                long natural = jvmLease(xmx);
                long bytes = Math.min(cap, natural);
                return new Demand(bytes, requested > 0 ? requested : 0, true, false, true, natural > cap);
            }
            long fitted = clampXmx(xmx, cap);
            long bytes = Math.min(cap, jvmLease(fitted));
            return new Demand(bytes, fitted, true, fitted < xmx || bytes < jvmLease(xmx), false, false);
        }
    }

    /** The engine ledger's capacity: {@link WorkerContainment#budgetBytes()}, cached for two seconds. */
    private static final class ProcessBudget implements LongSupplier {
        private volatile long bytes;
        private volatile long atNanos;

        @Override
        public long getAsLong() {
            long now = Clock.SYSTEM.nanos();
            long cached = bytes;
            if (cached > 0 && now - atNanos < 2_000_000_000L) return cached;
            long measured = WorkerContainment.budgetBytes();
            bytes = measured;
            atNanos = now;
            return measured;
        }
    }

    /** The step's output carries the wait; its label stays the step's own ("compiling", …). */
    private static void reportWaiting(long need, long free) {
        String line = waitingLine(need, free);
        Log.info("jk engine: " + line);
        TaskContext ctx = StepScope.current();
        if (ctx != null) ctx.output(line);
    }

    /** The engine-log line for one overbooked grant, after the bytes have been taken. */
    static String overbookedLine(long grantBytes, long leasedBytes, long budgetBytes, OverbookSignals.Reading reading) {
        long over = Math.max(0, leasedBytes - Math.max(0, budgetBytes));
        return "overbooked " + format(grantBytes)
                + " (leased " + format(leasedBytes)
                + " of " + format(budgetBytes)
                + ", " + format(over) + " over the budget, free " + format(Math.max(0, reading.freeBytes()))
                + ", psi some " + String.format(Locale.ROOT, "%.2f", reading.someAvg10())
                + " full " + String.format(Locale.ROOT, "%.2f", reading.fullAvg10()) + ")";
    }

    private static void noteGranted(Waiter waiter, long waitedNanos) {
        if (waitedNanos <= 0) return;
        Log.info("jk engine: " + waitedPhrase(waitedNanos));
        TaskContext ctx = StepScope.current();
        if (ctx == null) return;
        recordWait(ctx, waitedNanos);
    }

    /**
     * Count {@code waitedNanos} on {@code ctx}. Half a second or more is also a line on the step's
     * output and one share of the results' single wait line.
     */
    static void recordWait(TaskContext ctx, long waitedNanos) {
        if (ctx == null || waitedNanos <= 0) return;
        ctx.waited(Duration.ofNanos(waitedNanos));
        if (waitedNanos < OUTPUT_AFTER_NANOS) return;
        ctx.output(waitedPhrase(waitedNanos));
        MemoryNotes.add(ctx, waitedNanos);
    }

    /** {@code cpuSlot}: counts against the running-JVM cap; a resident helper between requests does not. */
    private record Demand(
            long bytes,
            long xmxBytes,
            boolean jvm,
            boolean clamped,
            boolean userPinned,
            boolean overBudget,
            boolean cpuSlot) {
        Demand(long bytes, long xmxBytes, boolean jvm, boolean clamped, boolean userPinned, boolean overBudget) {
            this(bytes, xmxBytes, jvm, clamped, userPinned, overBudget, jvm);
        }

        Demand resident() {
            return new Demand(bytes, xmxBytes, jvm, clamped, userPinned, overBudget, false);
        }
    }

    /** One owner's held leases, as {@link Snapshot#waiting()} names them. */
    private static final class Holder {
        final String owner;
        long bytes;
        int forks;
        boolean asked;

        Holder(String owner) {
            this.owner = owner;
        }

        void add(Waiter waiter) {
            bytes += waiter.demand.bytes + waiter.excess();
            forks++;
            asked |= waiter.yieldAsked;
        }
    }

    /** {@code job #7}, the resident's name, or {@code the engine} for a fork outside any job. */
    private static String ownerOf(Waiter waiter) {
        if (waiter.resident != null) return waiter.resident.name();
        if (waiter.requestId != null) return "job #" + waiter.requestId;
        return "the engine";
    }

    private static final class Waiter {
        final Demand demand;
        final @Nullable Long requestId;
        final @Nullable Resident resident;
        /** The resident has been asked to exit for the head of the queue. */
        boolean yieldAsked;

        boolean cancelled;
        boolean granted;
        boolean released;
        boolean overbooked;
        long sampleFree;
        double sampleSome;
        double sampleFull;

        /** The forked process, once {@link Grant#watch} names it; {@code -1} before. */
        long pid = -1;

        String what = "worker";

        @Nullable
        TaskContext step;
        /** Last resident set read, {@code -1} before the first. */
        long rss = -1;

        long peakRss;
        /** The last reading was {@linkplain WorkerRss#farOverLease far over} the lease. */
        boolean overLease;

        /** Named in the engine log, and on its step, for going far over its lease. */
        boolean named;

        Waiter(Demand demand, @Nullable Long requestId, @Nullable Resident resident) {
            this.demand = demand;
            this.requestId = requestId;
            this.resident = resident;
        }

        /** Resident bytes above the lease, charged on top of it. */
        long excess() {
            return Math.max(0, rss - demand.bytes);
        }

        /** {@code test JVM pid 4242 (job #7)}. */
        String label() {
            String owner = ownerOf(this);
            String base = resident != null ? owner : what;
            StringBuilder out = new StringBuilder(base);
            if (pid > 0) out.append(" pid ").append(pid);
            if (resident == null && requestId != null)
                out.append(" (").append(owner).append(')');
            return out.toString();
        }
    }

    /** A held lease. Closing returns the bytes; a second close does nothing. */
    public static final class Grant implements AutoCloseable {
        private final Ledger ledger;
        private final Waiter waiter;

        private Grant(Ledger ledger, Waiter waiter) {
            this.ledger = ledger;
            this.waiter = waiter;
        }

        public long bytes() {
            return waiter.demand.bytes;
        }

        /** The {@code -Xmx} to launch with, or {@code 0} for a tool. */
        public long xmxBytes() {
            return waiter.demand.xmxBytes;
        }

        public boolean clamped() {
            return waiter.demand.clamped;
        }

        public boolean jvm() {
            return waiter.demand.jvm;
        }

        /** The user wrote this heap. The command is launched with their flags. */
        public boolean userPinned() {
            return waiter.demand.userPinned;
        }

        /** The pin's natural lease was larger than the budget, so the grant is the whole budget. */
        public boolean overBudget() {
            return waiter.demand.overBudget;
        }

        /** Granted past the reservation budget, on a host sample that said it was safe. */
        public boolean overbooked() {
            return waiter.overbooked;
        }

        /**
         * Charge this lease the resident set of {@code pid} from now on. {@code what} names it
         * ({@link WorkerRss#describe}); {@code step} is the step that forked it, warned when the worker goes
         * {@linkplain WorkerRss#farOverLease far over} the lease. A closed grant ignores this.
         */
        public void watch(long pid, String what, @Nullable TaskContext step) {
            ledger.watch(waiter, pid, Objects.requireNonNull(what, "what"), step);
        }

        @Override
        public void close() {
            ledger.close(waiter);
        }
    }
}
