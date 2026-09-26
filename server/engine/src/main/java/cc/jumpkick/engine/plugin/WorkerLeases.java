// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.AvailableCpus;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import cc.jumpkick.run.TaskNames;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * first.
 *
 * <p>The queue and the counters live on a {@link Ledger}. {@link #engine()} is the one this process
 * uses: its capacity supplier is {@link WorkerContainment#budgetBytes()}, so {@code
 * JK_WORKER_BUDGET_MB} applies to that instance, its CPU cap is the host's core count, and a
 * waiting lease is abandoned once {@link JobWorkers#ended} says the request was shut down.
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

    /** {@code -Xmx} assumed for {@link TaskNames#NATIVE_IMAGE} when it carries no {@code -J-Xmx}. */
    static final long NATIVE_IMAGE_XMX = GIB;

    /** Smallest heap a clamped worker is given when the budget can hold it. */
    static final long MIN_XMX = 32 * MIB;

    private static final long REPORT_EVERY_NANOS = 2_000_000_000L;

    /** A finished wait shorter than this is recorded and not printed. */
    private static final long OUTPUT_AFTER_NANOS = 500_000_000L;

    private static final Pattern XMX = Pattern.compile("^(?<prefix>-J)?-Xmx(?<size>\\d+[kKmMgGtT]?)$");
    private static final Pattern XMS = Pattern.compile("^(?<prefix>-J)?-Xms(?<size>\\d+[kKmMgGtT]?)$");
    private static final Pattern XX_HEAP = Pattern.compile(
            "^(?<prefix>-J)?-XX:(?<name>MaxHeapSize|MinHeapSize|InitialHeapSize|SoftMaxHeapSize)=(?<size>\\d+[kKmMgGtT]?)$");

    /**
     * This process's ledger. Capacity is re-read from {@link WorkerContainment#budgetBytes()} at
     * most every two seconds; that read honors {@code JK_WORKER_BUDGET_MB}.
     */
    private static final Ledger ENGINE =
            new Ledger(new ProcessBudget(), () -> Math.max(1, AvailableCpus.count()), JobWorkers::ended);

    private WorkerLeases() {}

    /** The ledger {@link JobWorkers}, the compiler host, and {@code jk engine status} share. */
    public static Ledger engine() {
        return ENGINE;
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
     * where they were higher. A JVM command that named no heap gains an {@code -Xmx}.
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
        if (!sawXmx && !out.isEmpty()) out.add(1, "-Xmx" + mib(xmx) + "m");
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
    private static String executable(List<String> command) {
        if (command.isEmpty()) return "";
        if (command.size() > 1 && setsid(command.get(0))) return command.get(1);
        return command.get(0);
    }

    private static boolean setsid(String arg) {
        int slash = Math.max(arg.lastIndexOf('/'), arg.lastIndexOf('\\'));
        String name = slash >= 0 ? arg.substring(slash + 1) : arg;
        return name.equals("setsid");
    }

    private static boolean jvmCommand(List<String> command) {
        return !command.isEmpty() && jvmExecutable(executable(command));
    }

    private static long defaultXmx(List<String> command) {
        String exe = executable(command);
        return jvmExecutable(exe) && exe.toLowerCase(Locale.ROOT).contains(TaskNames.NATIVE_IMAGE)
                ? NATIVE_IMAGE_XMX
                : UNSIZED_JVM_XMX;
    }

    private static String prefix(Matcher matcher) {
        return matcher.group("prefix") == null ? "" : "-J";
    }

    private static long mib(long bytes) {
        return Math.max(1, bytes / MIB);
    }

    /** What {@code jk engine status} prints. */
    public record Snapshot(long budgetBytes, long leasedBytes, int queued, int runningJvms, int cpuCap) {}

    /**
     * One queue of waiters and the bytes and JVM slots currently handed out. {@code capacityBytes}
     * and {@code cpuCap} are read when a lease is granted, so a supplier may change what fits
     * without replacing the ledger.
     */
    public static final class Ledger {
        private final LongSupplier capacity;
        private final IntSupplier cpus;
        private final LongPredicate ended;
        private final Object lock = new Object();
        private final ArrayDeque<Waiter> queue = new ArrayDeque<>();
        private final ConcurrentHashMap<Long, AtomicLong> waited = new ConcurrentHashMap<>();
        private long leased;
        private int jvmRunning;

        /**
         * {@code ended} is true once that request has been shut down. A lease waiting for it is
         * abandoned instead of staying queued. The engine ledger passes {@link JobWorkers#ended}.
         */
        public Ledger(LongSupplier capacityBytes, IntSupplier cpuCap, LongPredicate ended) {
            this.capacity = capacityBytes;
            this.cpus = cpuCap;
            this.ended = ended;
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
                return new Snapshot(capacityBytes(), leased, queue.size(), jvmRunning, cpuCap());
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
            long arrived = Clock.SYSTEM.nanos();
            Waiter waiter = new Waiter(demand, requestId);
            boolean queued = false;
            synchronized (lock) {
                if (grantIfHead(waiter)) return finish(waiter, 0, false);
                queue.addLast(waiter);
                queued = true;
            }
            if (queued) {
                long free;
                synchronized (lock) {
                    free = Math.max(0, capacityBytes() - leased);
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
                        free = Math.max(0, capacityBytes() - leased);
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
            if (!queue.isEmpty() || !fits(candidate)) return false;
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
                if (!fits(head)) return;
                queue.pollFirst();
                take(head);
            }
        }

        private boolean fits(Waiter waiter) {
            long cap = capacityBytes();
            if (leased > cap || waiter.demand.bytes > cap - leased) return false;
            return !waiter.demand.jvm || jvmRunning < cpuCap();
        }

        private void take(Waiter waiter) {
            leased += waiter.demand.bytes;
            if (waiter.demand.jvm) jvmRunning++;
            waiter.granted = true;
        }

        private void release(Waiter waiter) {
            if (waiter.released) return;
            waiter.released = true;
            leased -= waiter.demand.bytes;
            if (leased < 0) leased = 0;
            if (waiter.demand.jvm) jvmRunning = Math.max(0, jvmRunning - 1);
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
            long xmx = requested > 0 ? requested : defaultXmx(command);
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

    private static void noteGranted(Waiter waiter, long waitedNanos) {
        TaskContext ctx = StepScope.current();
        if (waitedNanos <= 0) return;
        String phrase = waitedPhrase(waitedNanos);
        Log.info("jk engine: " + phrase);
        if (ctx == null) return;
        ctx.waited(Duration.ofNanos(waitedNanos));
        if (waitedNanos >= OUTPUT_AFTER_NANOS) ctx.output(phrase);
    }

    private record Demand(
            long bytes, long xmxBytes, boolean jvm, boolean clamped, boolean userPinned, boolean overBudget) {}

    private static final class Waiter {
        final Demand demand;
        final @Nullable Long requestId;
        boolean cancelled;
        boolean granted;
        boolean released;

        Waiter(Demand demand, @Nullable Long requestId) {
            this.demand = demand;
            this.requestId = requestId;
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

        @Override
        public void close() {
            ledger.close(waiter);
        }
    }
}
