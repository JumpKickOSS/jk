// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
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
 * line first, until the line stands still for {@link #WAIT_AT_MOST}, then fails naming what it
 * needed and who held it.
 * It may instead be granted past the budget while {@link OverbookSignals} say the host has room,
 * never past {@link #OVERBOOK_CAP_FACTOR} times the budget (less under {@code CI}); {@code
 * JK_OVERBOOK=0} turns that off. A running fork is never killed to make the room.
 *
 * <p>The lease is an estimate. Every {@link WorkerRss#SAMPLE_EVERY} the engine ledger reads each live
 * worker's resident set and charges the larger of the two, so a worker whose native memory
 * (metaspace, thread stacks, direct buffers) outgrows its lease holds back new grants by what it
 * really uses, and is named in the run's warnings, {@code jk engine status} and the engine log.
 *
 * <p>A {@linkplain LeaseResident resident} helper (the build-script host, a warm plugin worker, a
 * lingering Java compiler worker) holds its lease between requests and takes no JVM slot. While it
 * is {@linkplain LeaseResident#idle idle} its heap does not count toward the bound on what the held heaps
 * may grow to: an idle worker does not grow, and it is the first asked to give memory back. When the
 * head of the queue would fit the budget once residents let go, the ledger asks them to exit; they
 * finish the request in flight, exit, and start again on their next use. So the head waits only on
 * forks that are doing work, never on an idle holder.
 *
 * <p>The queue and the counters live on a {@link Ledger}. {@link #engine()} is the one this process
 * uses: its capacity supplier is {@link WorkerContainment#budgetBytes()}, so {@code
 * JK_WORKER_BUDGET_MB} applies to that instance, its CPU cap is the host's core count, a waiting
 * lease is abandoned once {@link JobWorkers#ended} says its request was shut down, and a lease past
 * the budget is granted only when {@link OverbookSignals#live()} says the host has room.
 *
 * <p>The budget, its cap and the JVM slot cap bound the host, not one process. An engine process
 * {@linkplain #shareHost joins} the {@link HostLeases} of its memory budget ({@link LeaseScope}),
 * and each grant counts the bytes and JVMs every other engine of that budget holds, whatever its
 * state directory, so a displaced engine still draining and its successor, or engines with
 * different homes side by side, together stay inside them. They lease against one budget and one
 * JVM cap, the ones the engine that was there first recorded. An engine started inside another
 * engine's worker, as a test's engine is inside a test JVM, keeps its own: that worker is charged
 * at its whole tree's resident set. A ledger that joins nothing, as in a test JVM, counts only its
 * own leases against its own limits.
 */
public final class WorkerLeases {

    /** How long the line may stand still, no lease granted to anyone, before a waiting lease fails. */
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
                    new ProcessMemory.Trees())
            .sampleEvery(WorkerRss.SAMPLE_EVERY);

    private static volatile @Nullable Ledger replaced;

    private WorkerLeases() {}

    /** The ledger {@link JobWorkers}, the compiler host, and {@code jk engine status} share. */
    public static Ledger engine() {
        Ledger r = replaced;
        return r != null ? r : PROCESS;
    }

    /**
     * Count every engine of this memory budget against this process's ledger from now on, sharing in
     * {@code engineDir} only when the budget's own directory cannot be used. Only the engine process
     * calls this, before it forks a worker; a test JVM's ledger stays its own.
     */
    public static void shareHost(Path engineDir) {
        PROCESS.shareHost(HostLeases.forThisProcess(LeaseScope.dir(engineDir)));
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
        private final ArrayDeque<LeaseWaiter> queue = new ArrayDeque<>();
        private final LinkedHashSet<LeaseWaiter> held = new LinkedHashSet<>();
        private final Set<TaskContext> warnedSteps = Collections.newSetFromMap(new WeakHashMap<>());
        private long leased;
        /** When this ledger last granted a lease: a waiter gives up only once the line stood still. */
        private long lastGrant = Long.MIN_VALUE;
        /** Sum over held workers of the resident bytes above their lease. */
        private long excess;
        /** Held workers {@linkplain WorkerRss#farOverLease far over their lease}. */
        private int overLeaseCount;

        private int jvmRunning;
        private Duration waitAtMost = WAIT_AT_MOST;
        /** Whether the heaps' bound is the one of a {@code CI} engine: {@link OverbookSignals#CAUTIOUS}. */
        private boolean cautious = OverbookSignals.CAUTIOUS;

        private @Nullable Duration sampleEvery;
        private boolean sampling;
        private @Nullable HostLeases host;

        /** Changes to this ledger's line for the other engines, counted; each line is recorded with its number. */
        private long version;

        /** A thread is recording this ledger's line ({@link #publishPending}). */
        private boolean publishing;

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
            Objects.requireNonNull(share, "share");
            long seq;
            HostLeases.Line line;
            synchronized (lock) {
                this.host = share;
                seq = ++version;
                line = line();
            }
            boolean recorded = share.publish(seq, line);
            synchronized (lock) {
                if (!recorded) changed();
            }
            return this;
        }

        /** Bound the held heaps as an engine started with {@code CI} set does, or as one without it. */
        Ledger underCi(boolean ci) {
            synchronized (lock) {
                this.cautious = ci;
            }
            return this;
        }

        /** {@link OverbookSignals#ceiling(long, boolean)} of {@code cap} for this ledger. */
        private long ceiling(long cap) {
            return OverbookSignals.ceiling(cap, cautious);
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

        /**
         * Under the lock: what other engines of this host hold, as last read, without touching the
         * shared file; nothing when shared with none. {@link #refresh} reads it again, off the lock.
         */
        private HostLeases.Reading others() {
            HostLeases h = host;
            return h == null ? HostLeases.Reading.NONE : h.cached();
        }

        /** Off the lock: read the other engines again when the last reading is more than 250 ms old. */
        private HostLeases.Reading refresh() {
            HostLeases h = host;
            return h == null ? HostLeases.Reading.NONE : h.recent();
        }

        /** Bytes the other engines of this host hold, read at most 250 ms ago; {@code 0} when shared with none. */
        public long otherEnginesBytes() {
            return refresh().bytes();
        }

        /** JVMs the other engines of this host run, read at most 250 ms ago; {@code 0} when shared with none. */
        public int otherEnginesJvms() {
            return refresh().jvms();
        }

        /** Leases the other engines of this host have queued, read at most 250 ms ago; {@code 0} when shared with none. */
        public int otherEnginesWaiting() {
            return refresh().waiting();
        }

        /**
         * Under the lock: this ledger's line changed. It is recorded for the other engines once the
         * lock is let go, on a thread of its own, so no lease, release or cancel waits on the shared
         * file for it.
         */
        private void changed() {
            version++;
            if (host == null || publishing) return;
            publishing = true;
            SessionContext.startVirtual("jk-lease-publish", this::publishPending);
        }

        /**
         * Record this ledger's newest line until the shared file holds it, offering a line the file
         * did not take again after a {@linkplain HostLeases#pauseBeforeRetry pause}: a release left
         * unrecorded would stay counted by every other engine. Each line carries its number, so one
         * made before a grant's confirmed line never replaces it.
         */
        private void publishPending() {
            int failures = 0;
            while (true) {
                HostLeases h;
                long seq;
                HostLeases.Line line;
                synchronized (lock) {
                    h = host;
                    if (h == null || h.recordedSeq() >= version) {
                        publishing = false;
                        return;
                    }
                    seq = version;
                    line = line();
                }
                if (h.publish(seq, line)) failures = 0;
                else if (!HostLeases.pauseBeforeRetry(++failures)) {
                    synchronized (lock) {
                        publishing = false;
                    }
                    return;
                }
            }
        }

        /** Test seam: no line of this ledger waits to be recorded for the other engines. */
        boolean recorded() {
            synchronized (lock) {
                return !publishing;
            }
        }

        /** Under the lock: what this ledger records for the other engines. */
        private HostLeases.Line line() {
            // The excess is rounded up for the other engines, so page-sized movement of a resident
            // set does not rewrite the shared file; this ledger still charges exact bytes.
            long bytes = leased + WorkerRss.published(excess);
            int jvms = jvmRunning;
            int waiting = queue.size();
            List<HostLeases.Worker> workers = new ArrayList<>();
            for (LeaseWaiter w : held) {
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

        /**
         * The ledger as a status line reads it. The other engines are read before this ledger's
         * lock is taken: that read waits on the shared file's lock, and a lease or a cancel must
         * not wait behind a status request for it.
         */
        public Snapshot snapshot() {
            HostLeases h = host;
            HostLeases.Reading others = h == null ? HostLeases.Reading.NONE : h.fresh();
            synchronized (lock) {
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

        /**
         * The JVM startup caches ({@code -XX:AOTCache=}) the workers holding a lease here were
         * started with, until each exits: {@link StartupCaches} never deletes them. Read from what
         * the engine put on each command line, so it holds on every OS, Windows included, where no
         * process's arguments can be read back.
         */
        public Set<Path> mappedCaches() {
            synchronized (lock) {
                return held.stream().flatMap(w -> w.maps.stream()).collect(Collectors.toSet());
            }
        }

        /** Leases waiting for capacity. */
        public int queued() {
            synchronized (lock) {
                return queue.size();
            }
        }

        /**
         * Block until {@code command}'s lease fits, classifying the heap with {@link
         * JvmOptions.HeapChoice#inspect}. A planned JVM lease larger than the budget is clamped
         * first; a user pin is not. Interrupt, {@link JobWorkers#ended} for {@code requestId}, or a
         * cancel of the step that asked ({@link TaskContext#cancelled}) abandons the wait.
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
            return acquire(LeaseDemand.of(command, choice, capacityBytes()), requestId);
        }

        /**
         * As {@link #acquire(List, Long, JvmOptions.HeapChoice)} for a JVM that takes no CPU slot: one
         * whose work runs in processes that lease their own slots, such as a test JVM that drives
         * engines of its own. It holding a slot for its whole life would keep that slot from the very
         * forks it waits on. Those engines lease from the JVM's own ledger, which no engine of the host
         * reads, so {@code choice}'s price, learned from the JVM's whole process tree, is leased even
         * past its own {@code -Xmx} lease.
         */
        public Grant acquireWithoutSlot(List<String> command, @Nullable Long requestId, JvmOptions.HeapChoice choice)
                throws InterruptedException {
            long capacity = capacityBytes();
            return acquire(
                    LeaseDemand.of(command, choice, capacity).drivingEngines(choice.priceBytes(), capacity), requestId);
        }

        /**
         * As {@link #acquire(List, Long, JvmOptions.HeapChoice)} for a helper that stays resident
         * between requests: it leases its memory but takes no CPU slot, so an idle helper never holds
         * back a fork that has work to run, and {@code resident} is asked to give the memory back
         * when the head of the queue needs it.
         */
        public Grant acquireResident(List<String> command, JvmOptions.HeapChoice choice, LeaseResident resident)
                throws InterruptedException {
            return acquire(new LeaseWaiter(
                    LeaseDemand.of(command, choice, capacityBytes()).withoutSlot(),
                    null,
                    Objects.requireNonNull(resident, "resident")));
        }

        /** As {@link #acquire(List, Long)} for a lease already measured in bytes. */
        public Grant acquireBytes(long bytes, boolean jvm, @Nullable Long requestId) throws InterruptedException {
            long cap = capacityBytes();
            long leasedBytes = Math.min(Math.max(1, bytes), Math.max(1, cap));
            boolean clamped = leasedBytes < bytes;
            return acquire(new LeaseDemand(leasedBytes, 0, jvm, clamped, false, false), requestId);
        }

        /**
         * As {@link #acquireBytes} for a resident helper whose memory is measured while it runs: it
         * takes no JVM slot, belongs to no request, and {@code resident} is asked to give the lease
         * back when the head of the queue needs its memory.
         */
        public Grant acquireResidentBytes(long bytes, LeaseResident resident) throws InterruptedException {
            long leasedBytes = Math.min(Math.max(1, bytes), Math.max(1, capacityBytes()));
            LeaseDemand demand = new LeaseDemand(leasedBytes, 0, true, leasedBytes < bytes, false, false).withoutSlot();
            return acquire(new LeaseWaiter(demand, null, Objects.requireNonNull(resident, "resident")));
        }

        /**
         * Drop every queued lease for {@code requestId}. Granted leases stay; the process kill path
         * releases those when the process exits.
         */
        public void cancelRequest(long requestId) {
            synchronized (lock) {
                for (LeaseWaiter waiter : queue) {
                    if (waiter.requestId != null && waiter.requestId == requestId) waiter.cancelled = true;
                }
                lock.notifyAll();
            }
        }

        private Grant acquire(LeaseDemand demand, @Nullable Long requestId) throws InterruptedException {
            return acquire(new LeaseWaiter(demand, requestId, null));
        }

        private Grant acquire(LeaseWaiter waiter) throws InterruptedException {
            @Nullable Long requestId = waiter.requestId;
            waiter.scope = waiter.resident == null ? StepScope.current() : null;
            long arrived = Clock.SYSTEM.nanos();
            long deadlineNanos;
            boolean head;
            refresh();
            synchronized (lock) {
                refuseIfItNeverFits(waiter);
                head = grantIfHead(waiter);
                if (!head) {
                    queue.addLast(waiter);
                    changed();
                }
                deadlineNanos = waitAtMost.toNanos();
            }
            if (head && confirm(waiter)) return finish(waiter, 0, 0, 0);
            ForkWait wait = new ForkWait(waiter, requestId, waiter.scope, waiter.resident != null, arrived);
            try {
                while (true) {
                    boolean granted;
                    long need;
                    long free;
                    @Nullable WaitReason reason;
                    refresh();
                    boolean stepCancelled = waiter.scope != null && waiter.scope.cancelled();
                    synchronized (lock) {
                        if (waiter.cancelled || stepCancelled || requestEnded(requestId)) {
                            queue.remove(waiter);
                            grantQueued();
                            changed();
                            lock.notifyAll();
                            throw new InterruptedException("cancelled while waiting for a worker lease");
                        }
                        grantQueued();
                        granted = waiter.granted;
                        if (!granted) {
                            long now = Clock.SYSTEM.nanos();
                            long still = now - Math.max(arrived, lastGrant);
                            long left = deadlineNanos - still;
                            if (left <= 0) giveUp(waiter, now - arrived, lastGrant > arrived ? still : -1);
                            else {
                                refuseIfItNeverFits(waiter);
                                lock.wait(Math.max(1, Math.min(250, TimeUnit.NANOSECONDS.toMillis(left))));
                            }
                        }
                        // Every waiter waits on what refused the head; grantQueued dropped a cancelled one.
                        reason = granted ? null : queue.isEmpty() ? waiter.reason : queue.peekFirst().reason;
                        need = waiter.demand.bytes();
                        free = Math.max(0, capacityBytes() - hostCharged());
                    }
                    if (granted) {
                        if (!confirm(waiter)) continue;
                        wait.granted(Clock.SYSTEM.nanos());
                        break;
                    }
                    wait.waiting(reason, need, free, Clock.SYSTEM.nanos());
                }
            } catch (InterruptedException | LeaseRefused e) {
                synchronized (lock) {
                    queue.remove(waiter);
                    if (waiter.granted) release(waiter);
                    grantQueued();
                    changed();
                    lock.notifyAll();
                }
                throw e;
            } finally {
                wait.close();
            }
            return finish(waiter, Clock.SYSTEM.nanos() - arrived, wait.memoryNanos(), wait.heldNanos());
        }

        /**
         * Count the granted fork as running for its build and step, and note a wait: {@code memoryNanos}
         * of {@code waitedNanos} were spent waiting for memory, {@code heldNanos} of them with the build
         * on hold.
         */
        private Grant finish(LeaseWaiter waiter, long waitedNanos, long memoryNanos, long heldNanos) {
            boolean run;
            synchronized (lock) {
                run = waiter.counted = waiter.resident == null && !waiter.released;
            }
            if (run) ForkWait.running(waiter.requestId, waiter.scope);
            if (waitedNanos > 0) MemoryNotes.noteGranted(waitedNanos, memoryNanos, heldNanos);
            return new Grant(this, waiter);
        }

        /**
         * Under the lock: refuse a lease whose worst case is past {@link #OVERBOOK_CAP_FACTOR} times
         * the budget. No state of this ledger can grant it, so waiting would never end.
         */
        private void refuseIfItNeverFits(LeaseWaiter waiter) {
            long cap = capacityBytes();
            long most = ceiling(cap);
            if (waiter.demand.worstBytes() <= most) return;
            String what = waiter.demand.pin().isEmpty()
                    ? waiter.owner() + " needs " + format(waiter.demand.worstBytes())
                    : "the pinned " + waiter.demand.pin() + " needs " + format(waiter.demand.worstBytes())
                            + " with its overhead";
            throw new LeaseRefused(what + ", over the " + format(most) + " a " + format(cap)
                    + " worker budget allows; lower the heap, or raise " + WorkerContainment.BUDGET_ENV);
        }

        /**
         * Under the lock: no lease was granted for {@link #WAIT_AT_MOST} while {@code waiter} waited.
         * Behind another waiter, it names the first in line, which is what it waited on. {@code
         * stillNanos} is how long the line stood still when leases were granted to others since the
         * waiter came, so a wait past the limit says the limit counts from the last grant; else
         * negative.
         */
        private void giveUp(LeaseWaiter waiter, long waitedNanos, long stillNanos) {
            long budget = capacityBytes();
            boolean slot = waiter.demand.cpuSlot() && hostJvms() >= cpuCap();
            String phrase = MemoryNotes.waitedPhrase(waitedNanos);
            if (slot) phrase = phrase.replace("for memory", "for a JVM slot");
            if (stillNanos >= 0)
                phrase += ", the last " + MemoryNotes.seconds(stillNanos) + " with no lease granted to anyone,";
            LeaseWaiter first = queue.peekFirst();
            String line = first == null || first == waiter
                    ? waitLine(waiter, budget, true)
                    : "needs " + format(waiter.demand.bytes()) + " behind " + first.owner() + ", first in line, which "
                            + waitLine(first, budget, true);
            throw new LeaseRefused(waiter.owner() + " " + phrase + " and gave up: " + line);
        }

        /** Under the lock: grant {@code candidate} when it is the only waiter and it fits. */
        private boolean grantIfHead(LeaseWaiter candidate) {
            if (!queue.isEmpty() || !admit(candidate)) return false;
            take(candidate);
            return true;
        }

        /** Under the lock: grant the front of the queue while it fits. */
        private void grantQueued() {
            while (!queue.isEmpty()) {
                LeaseWaiter head = queue.peekFirst();
                if (head.cancelled) {
                    queue.pollFirst();
                    changed();
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
         * Under the lock: when {@code head} is short only of what residents hold, its bytes or room
         * under the heap {@linkplain #ceilingBlocks ceiling}, ask enough of them to exit. A resident
         * gives back what it is charged, its lease or its larger resident set. Each resident is asked
         * once; its grant closes when its process is gone. While forks that are working still hold
         * what the head needs, residents are left alone.
         */
        private void reclaimFor(LeaseWaiter head) {
            if (head.demand.cpuSlot() && hostJvms() >= cpuCap()) return;
            long cap = capacityBytes();
            long bytesShort = head.demand.bytes() - (cap - hostCharged());
            long heapsShort = heaps() + head.demand.worstBytes() - ceiling(cap);
            long bytesLeft = 0;
            long heapsLeft = 0;
            for (LeaseWaiter w : held) {
                if (w.resident == null) continue;
                long bytes = chargedTo(w);
                long heap = heapOf(w);
                bytesShort -= w.yieldAsked ? bytes : 0;
                heapsShort -= w.yieldAsked ? heap : 0;
                bytesLeft += w.yieldAsked ? 0 : bytes;
                heapsLeft += w.yieldAsked ? 0 : heap;
            }
            if (bytesShort > bytesLeft || heapsShort > heapsLeft) return;
            List<LeaseWaiter> ask = new ArrayList<>();
            for (LeaseWaiter w : held) {
                if (bytesShort <= 0 && heapsShort <= 0) break;
                long heap = heapOf(w);
                // Asking helps only with what it gives back: an idle resident holds no room under the bound.
                if (w.resident == null || w.yieldAsked || (bytesShort <= 0 && heap == 0)) continue;
                w.yieldAsked = true;
                bytesShort -= chargedTo(w);
                heapsShort -= heap;
                ask.add(w);
            }
            for (LeaseWaiter w : ask) {
                LeaseResident r = Objects.requireNonNull(w.resident);
                Log.info("jk engine: asking the " + r.name() + " to exit: " + head.owner() + " needs "
                        + format(head.demand.bytes()) + " and the " + r.name() + " holds " + format(w.demand.bytes()));
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
            LeaseWaiter head = null;
            for (LeaseWaiter w : queue) {
                if (!w.cancelled) {
                    head = w;
                    break;
                }
            }
            if (head == null) return "";
            return head.owner() + " " + waitLine(head, budget, false);
        }

        /**
         * Under the lock: {@code needs 2.0 GiB (1.0 GiB free); held by job #3 3.0 GiB}. {@code
         * nameBudget} writes {@code free of the 4.0 GiB worker budget}.
         */
        private String waitLine(LeaseWaiter head, long budget, boolean nameBudget) {
            StringBuilder line = new StringBuilder("needs ");
            if (head.demand.cpuSlot() && hostJvms() >= cpuCap()) {
                line.append("a JVM slot (")
                        .append(hostJvms())
                        .append('/')
                        .append(cpuCap())
                        .append(" running)");
            } else {
                boolean heaps = ceilingBlocks(head, budget);
                line.append(format(head.demand.bytes()))
                        .append(heaps ? " with a " + format(head.demand.worstBytes()) + " heap ceiling" : "")
                        .append(" (")
                        .append(format(Math.max(0, budget - hostCharged())))
                        .append(" free")
                        .append(nameBudget ? " of the " + format(budget) + " worker budget" : "");
                if (heaps)
                    line.append(", but the held workers' heaps may grow to " + format(heaps()) + " of the "
                            + format(ceiling(budget)) + " allowed together");
                line.append(')');
            }
            LeaseHolders holders = new LeaseHolders();
            for (LeaseWaiter w : held) holders.add(w.owner(), w.demand.bytes() + w.excess(), w.yieldAsked);
            return line.append(holders.line(others().bytes(), HOLDERS_SHOWN)).toString();
        }

        /**
         * Under the lock. A JVM still needs a free core. What the held forks and this one may grow
         * to, their {@code -Xmx} leases, stays inside {@link #OVERBOOK_CAP_FACTOR} times the budget.
         * Memory fits the reservation, or — when this
         * ledger has signals — the host sample covers the new lease's worst case and the reserved
         * total stays inside {@link #OVERBOOK_CAP_FACTOR} times the budget. Existing forks are not
         * re-charged here: the sample's free bytes are their real usage.
         *
         * <p>With a {@link HostLeases} share the bytes, the cap and the JVM slots include the other
         * engines as last read. A grant made on that reading is tentative until {@link #confirm}
         * judges it again on a fresh one.
         */
        private boolean admit(LeaseWaiter waiter) {
            return admit(waiter, others());
        }

        /**
         * Off the lock: let {@code waiter}'s tentative grant stand only when a fresh reading of the
         * other engines, taken under the shared file's lock, still admits it, and record the line
         * that counts it before that lock is let go. That is what keeps two engines from both
         * granting the last room. A grant the fresh reading refuses is undone and goes back to the
         * front of the queue. True when the grant stands; always, when shared with no other engine.
         */
        private boolean confirm(LeaseWaiter waiter) {
            HostLeases h = host;
            if (h == null) {
                synchronized (lock) {
                    if (waiter.overbooked) logOverbook(waiter);
                }
                return true;
            }
            boolean[] stands = {false};
            long[] seq = {-1};
            h.exchange(others -> {
                synchronized (lock) {
                    untake(waiter);
                    stands[0] = admit(waiter, others);
                    if (stands[0]) take(waiter);
                    else queue.addFirst(waiter);
                    seq[0] = ++version;
                    return new HostLeases.Publication(seq[0], line());
                }
            });
            synchronized (lock) {
                if (h.recordedSeq() < seq[0]) changed();
                if (stands[0] && waiter.overbooked) logOverbook(waiter);
                if (!stands[0]) lock.notifyAll();
            }
            return stands[0];
        }

        /** Under the lock: {@link #admit(LeaseWaiter)} with {@code others} held by other engines. */
        private boolean admit(LeaseWaiter waiter, HostLeases.Reading others) {
            if (waiter.demand.cpuSlot() && (long) jvmRunning + others.jvms() >= cpuCap())
                return refused(waiter, WaitReason.SLOT);
            long cap = capacityBytes();
            if (ceilingBlocks(waiter, cap)) return refused(waiter, WaitReason.MEMORY);
            long charged = charged() + Math.max(0, others.bytes());
            if (charged < 0) charged = Long.MAX_VALUE;
            if (charged <= cap && waiter.demand.bytes() <= cap - charged) {
                waiter.overbooked = false;
                return true;
            }
            return overbook(waiter, cap, charged) || refused(waiter, WaitReason.MEMORY);
        }

        /**
         * Under the lock: what the held forks and {@code waiter} may grow to, their {@code -Xmx}
         * leases, would pass {@link OverbookSignals#ceiling} of {@code cap}, however much of the
         * budget is free.
         */
        private boolean ceilingBlocks(LeaseWaiter waiter, long cap) {
            return heaps() > ceiling(cap) - waiter.demand.worstBytes();
        }

        /** Under the lock: what the held forks may grow to, each its {@code -Xmx} lease; idle residents count none. */
        private long heaps() {
            return held.stream().mapToLong(Ledger::heapOf).sum();
        }

        /** Under the lock: {@code w}'s {@code -Xmx} lease, or none while it is a resident between requests. */
        private static long heapOf(LeaseWaiter w) {
            LeaseResident r = w.resident;
            return r != null && r.idle().getAsBoolean() ? 0 : w.demand.worstBytes();
        }

        /** Under the lock: what {@code w} is charged, its lease or its last resident set when larger. */
        private static long chargedTo(LeaseWaiter w) {
            return w.demand.bytes() + w.excess();
        }

        private static boolean refused(LeaseWaiter waiter, WaitReason reason) {
            waiter.reason = reason;
            return false;
        }

        private boolean overbook(LeaseWaiter waiter, long cap, long charged) {
            if (cap <= 0) return false;
            long bytes = waiter.demand.bytes();
            long ceiling = ceiling(cap);
            if (overLeaseCount > 0) return false;
            if (bytes > ceiling || charged > ceiling - bytes) return false;
            OverbookSignals.Reading reading = readSignals();
            if (!reading.enabled() || !reading.pressureLow()) return false;
            if (!reading.covers(waiter.demand.worstBytes(), OverbookSignals.headroom(cap, cautious))) return false;
            waiter.overbooked = true;
            waiter.sample = reading;
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

        /** Under the lock: grant {@code waiter}; {@link #confirm} decides whether the grant stands. */
        private void take(LeaseWaiter waiter) {
            held.add(waiter);
            leased += waiter.demand.bytes();
            lastGrant = Clock.SYSTEM.nanos();
            if (waiter.demand.cpuSlot()) jvmRunning++;
            waiter.granted = true;
        }

        /** Under the lock: undo {@link #take} of a grant not yet confirmed, before it is judged again. */
        private void untake(LeaseWaiter waiter) {
            held.remove(waiter);
            leased = Math.max(0, leased - waiter.demand.bytes());
            if (waiter.demand.cpuSlot()) jvmRunning = Math.max(0, jvmRunning - 1);
            waiter.granted = false;
        }

        private void logOverbook(LeaseWaiter waiter) {
            Log.info("jk engine: "
                    + OverbookSignals.overbookedLine(
                            waiter.demand.bytes(), hostCharged(), capacityBytes(), waiter.sample));
        }

        private void release(LeaseWaiter waiter) {
            if (waiter.released) return;
            waiter.released = true;
            held.remove(waiter);
            leased = Math.max(0, leased - waiter.demand.bytes());
            excess = Math.max(0, excess - waiter.excess());
            if (waiter.overLease) overLeaseCount = Math.max(0, overLeaseCount - 1);
            if (waiter.demand.cpuSlot()) jvmRunning = Math.max(0, jvmRunning - 1);
            changed();
            if (waiter.named) {
                Log.info("jk engine: " + waiter.label() + " exited; resident set peaked at " + format(waiter.peakRss)
                        + ", leased " + format(waiter.demand.bytes()));
            }
        }

        /**
         * Read every watched worker's resident set once and charge it. A worker that first goes
         * {@linkplain WorkerRss#farOverLease far over} its lease is named: in the engine log, and as a run
         * warning on the step that forked it, once per step. Reads happen off the lock.
         */
        public void sample() {
            List<LeaseWaiter> watched = new ArrayList<>();
            synchronized (lock) {
                for (LeaseWaiter w : held) if (w.pid > 0) watched.add(w);
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
            List<LeaseWaiter> crossed = new ArrayList<>();
            List<TaskContext> warnOn = new ArrayList<>();
            synchronized (lock) {
                for (int i = 0; i < readings.length; i++) {
                    LeaseWaiter w = watched.get(i);
                    if (w.released || readings[i] < 0) continue;
                    excess -= w.excess();
                    w.rss = readings[i];
                    w.peakRss = Math.max(w.peakRss, w.rss);
                    excess += w.excess();
                    boolean over = WorkerRss.farOverLease(w.demand.bytes(), w.rss);
                    if (over != w.overLease) overLeaseCount += over ? 1 : -1;
                    w.overLease = over;
                    if (over && !w.named) {
                        w.named = true;
                        crossed.add(w);
                        TaskContext step = w.step;
                        warnOn.add(step != null && warnedSteps.add(step) ? step : null);
                    }
                }
                changed();
                grantQueued();
                lock.notifyAll();
            }
            for (int i = 0; i < crossed.size(); i++) {
                LeaseWaiter w = crossed.get(i);
                String phrase = WorkerRss.overLeasePhrase(w.what, w.rss, w.demand.bytes());
                Log.warn("jk engine: " + WorkerRss.overLeasePhrase(w.label(), w.rss, w.demand.bytes())
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
            List<LeaseWaiter> over = new ArrayList<>();
            for (LeaseWaiter w : held) if (w.overLease) over.add(w);
            over.sort((a, b) -> Long.compare(b.rss, a.rss));
            StringBuilder line = new StringBuilder();
            int shown = Math.min(HOLDERS_SHOWN, over.size());
            for (int i = 0; i < shown; i++) {
                LeaseWaiter w = over.get(i);
                if (i > 0) line.append("; ");
                line.append(WorkerRss.overLeasePhrase(w.label(), w.rss, w.demand.bytes()));
            }
            if (over.size() > shown)
                line.append("; ").append(over.size() - shown).append(" more");
            return line.toString();
        }

        private void watch(LeaseWaiter waiter, long pid, String what, @Nullable TaskContext step) {
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
                changed();
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
        private void resize(LeaseWaiter waiter, long bytes) {
            synchronized (lock) {
                if (waiter.released || !waiter.granted) return;
                long next = Math.max(1, Math.min(bytes, Math.max(1, capacityBytes())));
                if (next == waiter.demand.bytes()) return;
                excess = Math.max(0, excess - waiter.excess());
                leased = Math.max(0, leased + next - waiter.demand.bytes());
                waiter.demand = waiter.demand.withBytes(next);
                excess += waiter.excess();
                boolean over = WorkerRss.farOverLease(next, waiter.rss);
                if (over != waiter.overLease) overLeaseCount = Math.max(0, overLeaseCount + (over ? 1 : -1));
                waiter.overLease = over;
                changed();
                grantQueued();
                lock.notifyAll();
            }
        }

        private void close(LeaseWaiter waiter) {
            boolean counted;
            synchronized (lock) {
                counted = waiter.counted;
                waiter.counted = false;
                release(waiter);
                grantQueued();
                lock.notifyAll();
            }
            if (counted) ForkWait.exited(waiter.requestId, waiter.scope);
        }

        private boolean requestEnded(@Nullable Long requestId) {
            return requestId != null && ended.test(requestId);
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
        private final LeaseWaiter waiter;

        private Grant(Ledger ledger, LeaseWaiter waiter) {
            this.ledger = ledger;
            this.waiter = waiter;
        }

        public long bytes() {
            return waiter.demand.bytes();
        }

        /** The {@code -Xmx} to launch with, or {@code 0} for a tool. */
        public long xmxBytes() {
            return waiter.demand.xmxBytes();
        }

        public boolean clamped() {
            return waiter.demand.clamped();
        }

        public boolean jvm() {
            return waiter.demand.jvm();
        }

        /** The user wrote this heap. The command is launched with their flags. */
        public boolean userPinned() {
            return waiter.demand.userPinned();
        }

        /** The pin's natural lease was larger than the budget, so the grant is the whole budget. */
        public boolean overBudget() {
            return waiter.demand.overBudget();
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
         * The startup caches the leased process is started with ({@link StartupCaches#mappedBy} of
         * its command), listed by {@link Ledger#mappedCaches} while the lease is held. Set before the
         * process starts, so no sweep sees it mapped and unlisted.
         */
        public void maps(Set<Path> caches) {
            waiter.maps = Set.copyOf(caches);
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
