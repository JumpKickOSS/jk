// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.run.TaskContext;
import java.nio.file.Path;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
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
 * keeps its flags and leases the whole budget. A pin past {@link #OVERBOOK_CAP_FACTOR} times the
 * budget is refused at once with {@link LeaseRefused}. A lease that does not fit yet waits, first in
 * line first, for at most {@link #WAIT_AT_MOST}, then fails naming what it needed and who held it.
 * On Linux it may instead be granted past the budget while {@link OverbookSignals} say the host has
 * room, never past {@link #OVERBOOK_CAP_FACTOR} times the budget; {@code CI} and {@code
 * JK_OVERBOOK=0} turn that off. A running fork is never killed to make the room.
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
 * lease is abandoned once {@link JobWorkers#ended} says its request was shut down, and a lease past
 * the budget is granted only when {@link OverbookSignals#live()} says the host has room.
 *
 * <p>The budget, its cap and the JVM slot cap bound the host, not one process. An engine process
 * {@linkplain #shareHost joins} its state directory's {@link HostLeases}, and each grant counts the
 * bytes and JVMs every other engine of that directory holds, so a displaced engine still draining
 * and its successor together stay inside them. They lease against one budget and one JVM cap, the
 * ones the engine that was there first recorded. A ledger that joins nothing, as in a test JVM,
 * counts only its own leases against its own limits.
 */
public final class WorkerLeases {

    /** How long a lease that could fit waits before it fails. */
    public static final Duration WAIT_AT_MOST = Duration.ofMinutes(10);

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

    /** Smallest heap a clamped worker is given when the budget can hold it. */
    static final long MIN_XMX = 32 * MIB;

    private static final long REPORT_EVERY_NANOS = 2_000_000_000L;

    /** Holders {@link Snapshot#waiting()} names before it counts the rest. */
    private static final int HOLDERS_SHOWN = 3;

    /**
     * Reserved bytes, including the lease being granted, stay at or under this many times the
     * budget. The cap is what stops a burst of grants on one cached sample.
     */
    public static final int OVERBOOK_CAP_FACTOR = 2;

    /**
     * This process's ledger. Capacity is re-read from {@link WorkerContainment#budgetBytes()} at
     * most every two seconds; that read honors {@code JK_WORKER_BUDGET_MB}.
     */
    private static final Ledger PROCESS = new Ledger(
                    new EngineLimits.Budget(),
                    EngineLimits::forkCpuCap,
                    JobWorkers::ended,
                    OverbookSignals.live(),
                    MemoryProbe::rssBytes)
            .sampleEvery(WorkerRss.SAMPLE_EVERY);

    private static volatile @Nullable Ledger replaced;

    private WorkerLeases() {}

    /** The ledger {@link JobWorkers}, the compiler host, and {@code jk engine status} share. */
    public static Ledger engine() {
        Ledger r = replaced;
        return r != null ? r : PROCESS;
    }

    /**
     * Count every engine of {@code engineDir} against this process's ledger from now on. Only the
     * engine process calls this, before it forks a worker; a test JVM's ledger stays its own.
     */
    public static void shareHost(Path engineDir) {
        PROCESS.shareHost(HostLeases.forThisProcess(engineDir));
    }

    /** Test seam: {@link #engine()} answers {@code ledger}, or this process's own when {@code null}. */
    public static void engineForTests(@Nullable Ledger ledger) {
        replaced = ledger;
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

    /** {@code 512 MiB} or {@code 1.5 GiB}. */
    public static String format(long bytes) {
        if (bytes >= GIB) {
            return String.format(Locale.ROOT, "%.1f GiB", bytes / (double) GIB);
        }
        return Math.max(0, bytes / MIB) + " MiB";
    }

    /**
     * What {@code jk engine status} prints. {@code leasedBytes} charges each worker the larger of its
     * lease and its last measured resident set, and adds what the other engines of this host hold.
     * {@code overbookedBytes} is the part of {@code
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
        private Duration waitAtMost = WAIT_AT_MOST;
        private @Nullable Duration sampleEvery;
        private boolean sampling;
        private @Nullable HostLeases host;

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

        /** Count the other engines {@code share} records in every grant, and record this ledger's line there. */
        Ledger shareHost(HostLeases share) {
            synchronized (lock) {
                this.host = Objects.requireNonNull(share, "share");
                share.publish(line(null));
            }
            return this;
        }

        /** Fail a lease that has waited {@code most} without being granted. */
        public Ledger waitAtMost(Duration most) {
            synchronized (lock) {
                this.waitAtMost = Objects.requireNonNull(most, "most");
            }
            return this;
        }

        /** Bytes charged against the budget: each held lease, or its worker's resident set when larger. */
        private long charged() {
            return leased + excess;
        }

        /** Under the lock: {@link #charged()} plus what the other engines of this host hold, as last read. */
        private long hostCharged() {
            long sum = charged() + others().bytes();
            return sum < 0 ? Long.MAX_VALUE : sum;
        }

        /** Under the lock: JVMs running on this host, this ledger's and the other engines' as last read. */
        private int hostJvms() {
            return (int) Math.min(Integer.MAX_VALUE, (long) jvmRunning + others().jvms());
        }

        /** What other engines of this host hold, as last read; nothing when shared with none. */
        private HostLeases.Reading others() {
            HostLeases h = host;
            return h == null ? HostLeases.Reading.NONE : h.recent();
        }

        /** Bytes the other engines of this host hold, read at most 250 ms ago; {@code 0} when shared with none. */
        public long otherEnginesBytes() {
            return others().bytes();
        }

        /** JVMs the other engines of this host run, read at most 250 ms ago; {@code 0} when shared with none. */
        public int otherEnginesJvms() {
            return others().jvms();
        }

        /** Leases the other engines of this host have queued, read at most 250 ms ago; {@code 0} when shared with none. */
        public int otherEnginesWaiting() {
            return others().waiting();
        }

        /** Under the lock: record this ledger's line for the other engines. */
        private void publish() {
            HostLeases h = host;
            if (h != null) h.publish(line(null));
        }

        /**
         * Under the lock: what this ledger records for the other engines, with {@code granting}'s
         * lease and slot counted, and its place in the queue not, when it is about to be taken.
         */
        private HostLeases.Line line(@Nullable Waiter granting) {
            long bytes = charged();
            int jvms = jvmRunning;
            int waiting = queue.size();
            if (granting != null) {
                bytes += granting.demand.bytes;
                if (granting.demand.cpuSlot) jvms++;
                if (queue.peekFirst() == granting) waiting--;
            }
            List<HostLeases.Worker> workers = new ArrayList<>();
            for (Waiter w : held) {
                if (w.pid > 0) workers.add(new HostLeases.Worker(w.pid, w.startedAtMillis));
            }
            return new HostLeases.Line(ownCapacityBytes(), ownCpuCap(), bytes, jvms, waiting, workers);
        }

        /** This process's own budget, before any other engine's recorded one replaces it. */
        private long ownCapacityBytes() {
            return Math.max(0, capacity.getAsLong());
        }

        /**
         * Bytes this ledger will hand out: its own budget, or, when it shares the host, the budget the
         * first engine there recorded.
         */
        public long capacityBytes() {
            long own = ownCapacityBytes();
            HostLeases h = host;
            return h == null ? own : h.budget(own);
        }

        /** This process's own JVM cap, before any other engine's recorded one replaces it. */
        private int ownCpuCap() {
            return Math.max(1, cpus.getAsInt());
        }

        /**
         * How many forked JVMs may run at once: this ledger's own cap, or, when it shares the host, the
         * cap the first engine there recorded.
         */
        public int cpuCap() {
            int own = ownCpuCap();
            HostLeases h = host;
            return h == null ? own : h.cpuCap(own);
        }

        public Snapshot snapshot() {
            synchronized (lock) {
                HostLeases h = host;
                HostLeases.Reading others = h == null ? HostLeases.Reading.NONE : h.fresh();
                long budget = capacityBytes();
                long charged = charged() + others.bytes();
                if (charged < 0) charged = Long.MAX_VALUE;
                long over = charged > budget ? charged - budget : 0;
                return new Snapshot(
                        budget,
                        charged,
                        over,
                        queue.size(),
                        hostJvms(),
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
            long deadlineNanos;
            synchronized (lock) {
                refuseIfItNeverFits(waiter);
                if (grantIfHead(waiter)) return finish(waiter, 0, false);
                queue.addLast(waiter);
                publish();
                queued = true;
                deadlineNanos = waitAtMost.toNanos();
            }
            if (queued) {
                long free;
                synchronized (lock) {
                    free = Math.max(0, capacityBytes() - hostCharged());
                }
                MemoryNotes.reportWaiting(waiter.demand.bytes, free);
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
                            publish();
                            lock.notifyAll();
                            throw new InterruptedException("cancelled while waiting for memory");
                        }
                        grantQueued();
                        granted = waiter.granted;
                        if (!granted) {
                            long left = deadlineNanos - (Clock.SYSTEM.nanos() - arrived);
                            if (left <= 0) giveUp(waiter, deadlineNanos);
                            else {
                                refuseIfItNeverFits(waiter);
                                lock.wait(Math.max(1, Math.min(250, TimeUnit.NANOSECONDS.toMillis(left))));
                            }
                        }
                        need = waiter.demand.bytes;
                        free = Math.max(0, capacityBytes() - hostCharged());
                    }
                    if (granted) break;
                    long now = Clock.SYSTEM.nanos();
                    if (lastReport == 0 || now - lastReport >= REPORT_EVERY_NANOS) {
                        MemoryNotes.reportWaiting(need, free);
                        lastReport = now;
                    }
                }
            } catch (InterruptedException | LeaseRefused e) {
                synchronized (lock) {
                    queue.remove(waiter);
                    if (waiter.granted) release(waiter);
                    grantQueued();
                    publish();
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
            if (queued) MemoryNotes.noteGranted(waitedNanos);
            return new Grant(this, waiter);
        }

        /**
         * Under the lock: refuse a lease whose worst case is past {@link #OVERBOOK_CAP_FACTOR} times
         * the budget. No state of this ledger can grant it, so waiting would never end.
         */
        private void refuseIfItNeverFits(Waiter waiter) {
            long cap = capacityBytes();
            long most = overbookCeiling(cap);
            if (waiter.demand.worstBytes <= most) return;
            String what = waiter.demand.pin.isEmpty()
                    ? ownerOf(waiter) + " needs " + format(waiter.demand.worstBytes)
                    : "the pinned " + waiter.demand.pin + " needs " + format(waiter.demand.worstBytes)
                            + " with its overhead";
            throw new LeaseRefused(what + ", over the " + format(most) + " a " + format(cap)
                    + " worker budget allows; lower the heap, or raise " + WorkerContainment.BUDGET_ENV);
        }

        /** Under the lock: {@code waiter} reached its deadline. */
        private void giveUp(Waiter waiter, long deadlineNanos) {
            long budget = capacityBytes();
            boolean slot = waiter.demand.cpuSlot && hostJvms() >= cpuCap();
            String phrase = MemoryNotes.waitedPhrase(deadlineNanos);
            if (slot) phrase = phrase.replace("for memory", "for a JVM slot");
            throw new LeaseRefused(ownerOf(waiter) + " " + phrase + " and gave up: " + waitLine(waiter, budget, true));
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
                    publish();
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
            if (head.demand.cpuSlot && hostJvms() >= cpuCap()) return;
            long cap = capacityBytes();
            long resident = 0;
            long yielding = 0;
            for (Waiter w : held) {
                if (w.resident == null) continue;
                resident += w.demand.bytes;
                if (w.yieldAsked) yielding += w.demand.bytes;
            }
            long charged = hostCharged();
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
            return ownerOf(head) + " " + waitLine(head, budget, false);
        }

        /**
         * Under the lock: {@code needs 2.0 GiB (1.0 GiB free); held by job #3 3.0 GiB}. {@code
         * nameBudget} writes {@code free of the 4.0 GiB worker budget}.
         */
        private String waitLine(Waiter head, long budget, boolean nameBudget) {
            StringBuilder line = new StringBuilder("needs ");
            if (head.demand.cpuSlot && hostJvms() >= cpuCap()) {
                line.append("a JVM slot (")
                        .append(hostJvms())
                        .append('/')
                        .append(cpuCap())
                        .append(" running)");
            } else {
                line.append(format(head.demand.bytes))
                        .append(" (")
                        .append(format(Math.max(0, budget - hostCharged())))
                        .append(" free");
                if (nameBudget) line.append(" of the ").append(format(budget)).append(" worker budget");
                line.append(')');
            }
            Map<String, Holder> holders = new LinkedHashMap<>();
            for (Waiter w : held) {
                holders.computeIfAbsent(ownerOf(w), Holder::new).add(w);
            }
            long others = others().bytes();
            if (others > 0) holders.computeIfAbsent(OTHER_ENGINES, Holder::new).bytes += others;
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
         *
         * <p>With a {@link HostLeases} share the bytes, the cap and the JVM slots include the other
         * engines. A recent reading of them may refuse; a grant is decided on a fresh one, under the
         * share's file lock, and the new line is recorded before the lock is let go.
         */
        private boolean admit(Waiter waiter) {
            HostLeases h = host;
            if (h == null) return admit(waiter, HostLeases.Reading.NONE);
            if (!admit(waiter, h.recent())) return false;
            boolean[] granted = {false};
            h.exchange(others -> {
                granted[0] = admit(waiter, others);
                return line(granted[0] ? waiter : null);
            });
            return granted[0];
        }

        /** Under the lock: {@link #admit(Waiter)} with {@code others} held by other engines. */
        private boolean admit(Waiter waiter, HostLeases.Reading others) {
            if (waiter.demand.cpuSlot && (long) jvmRunning + others.jvms() >= cpuCap()) return false;
            long cap = capacityBytes();
            long charged = charged() + Math.max(0, others.bytes());
            if (charged < 0) charged = Long.MAX_VALUE;
            if (charged <= cap && waiter.demand.bytes <= cap - charged) {
                waiter.overbooked = false;
                return true;
            }
            return overbook(waiter, cap, charged);
        }

        private boolean overbook(Waiter waiter, long cap, long charged) {
            if (cap <= 0) return false;
            long bytes = waiter.demand.bytes;
            long ceiling = overbookCeiling(cap);
            if (overLeaseCount > 0) return false;
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
                    + OverbookSignals.overbookedLine(
                            waiter.demand.bytes,
                            hostCharged(),
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
            publish();
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
                publish();
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
            long started = ProcessHandle.of(pid)
                    .map(p -> HostLeases.Owner.of(p).startedAtMillis())
                    .orElse(0L);
            Duration every = null;
            synchronized (lock) {
                if (waiter.released) return;
                waiter.pid = pid;
                waiter.startedAtMillis = started;
                waiter.what = what;
                waiter.step = step;
                publish();
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

        /**
         * Move a held lease to {@code bytes}, at least one byte and at most the budget. A lease that
         * grows past what is free is not refused: the process already holds the memory, and charging
         * it is what makes the next fork wait. A lease that shrinks lets queued forks in at once.
         */
        private void resize(Waiter waiter, long bytes) {
            synchronized (lock) {
                if (waiter.released || !waiter.granted) return;
                long next = Math.max(1, Math.min(bytes, Math.max(1, capacityBytes())));
                if (next == waiter.demand.bytes) return;
                excess = Math.max(0, excess - waiter.excess());
                leased = Math.max(0, leased + next - waiter.demand.bytes);
                waiter.demand = waiter.demand.withBytes(next);
                excess += waiter.excess();
                boolean over = WorkerRss.farOverLease(next, waiter.rss);
                if (over != waiter.overLease) overLeaseCount = Math.max(0, overLeaseCount + (over ? 1 : -1));
                waiter.overLease = over;
                publish();
                grantQueued();
                lock.notifyAll();
            }
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
            long requested = HeapFlags.parseXmx(command);
            if (requested <= 0 && choice.xmxBytes() > 0) requested = choice.xmxBytes();
            boolean jvm = requested > 0 || HeapFlags.jvmCommand(command);
            if (!jvm) {
                long bytes = Math.min(TOOL_BYTES, cap);
                return new Demand(bytes, 0, false, bytes < TOOL_BYTES, false, false);
            }
            long xmx = requested > 0 ? requested : UnsizedHeap.lease(command, cap);
            if (choice.userPinned()) {
                long natural = jvmLease(xmx);
                long bytes = Math.min(cap, natural);
                String pin = choice.label().isEmpty() ? "heap" : choice.label();
                return new Demand(
                        bytes, requested > 0 ? requested : 0, true, false, true, natural > cap, true, natural, pin);
            }
            long fitted = clampXmx(xmx, cap);
            long bytes = Math.min(cap, jvmLease(fitted));
            return new Demand(bytes, fitted, true, fitted < xmx || bytes < jvmLease(xmx), false, false);
        }
    }

    /**
     * {@code cpuSlot}: counts against the running-JVM cap; a resident helper between requests does
     * not. {@code worstBytes} is what the process may use: {@code bytes}, or a pin's whole lease when
     * {@code bytes} is the budget it was cut to. {@code pin} names that pin, {@code ""} otherwise.
     */
    private record Demand(
            long bytes,
            long xmxBytes,
            boolean jvm,
            boolean clamped,
            boolean userPinned,
            boolean overBudget,
            boolean cpuSlot,
            long worstBytes,
            String pin) {
        Demand(long bytes, long xmxBytes, boolean jvm, boolean clamped, boolean userPinned, boolean overBudget) {
            this(bytes, xmxBytes, jvm, clamped, userPinned, overBudget, jvm, bytes, "");
        }

        Demand resident() {
            return new Demand(bytes, xmxBytes, jvm, clamped, userPinned, overBudget, false, worstBytes, pin);
        }

        /** The same demand leasing {@code next} bytes. */
        Demand withBytes(long next) {
            return new Demand(next, xmxBytes, jvm, clamped, userPinned, overBudget, cpuSlot, next, pin);
        }
    }

    /** {@link #OVERBOOK_CAP_FACTOR} times {@code cap}, saturating. */
    static long overbookCeiling(long cap) {
        long budget = Math.max(0, cap);
        return budget > Long.MAX_VALUE / OVERBOOK_CAP_FACTOR ? Long.MAX_VALUE : budget * OVERBOOK_CAP_FACTOR;
    }

    /** The holder {@link Snapshot#waiting()} names for leases of other engines of this host. */
    private static final String OTHER_ENGINES = "other engines";

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
        /** What is leased; only {@link Ledger#resize} replaces it, under the ledger's lock. */
        Demand demand;

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

        /** When {@link #pid} started, in epoch milliseconds; {@code 0} when the OS did not say. */
        long startedAtMillis;

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

    /** A lease the ledger will not grant: larger than it can ever hold, or not granted in time. */
    public static final class LeaseRefused extends RuntimeException {
        LeaseRefused(String message) {
            super(message);
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

        /**
         * Lease {@code bytes} from now on instead: for a worker whose use is reported while it runs,
         * so the ledger charges what it holds rather than the most it could. A closed grant ignores
         * this.
         */
        public void resize(long bytes) {
            ledger.resize(waiter, bytes);
        }

        @Override
        public void close() {
            ledger.close(waiter);
        }
    }
}
