// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.config.AvailableCpus;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.HeapPlan;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.engine.plugin.PluginProcess;
import cc.jumpkick.engine.plugin.WorkerAotCache;
import cc.jumpkick.engine.plugin.WorkerContainment;
import cc.jumpkick.engine.plugin.WorkerLeases;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * A pool of Zinc {@code jk-java-compiler} JVMs per job ({@link JobWorkers} request scope). Compile
 * and {@code jk explain} PLAN share the pool; {@link #end(long)} / job teardown sends {@code DONE}
 * to every lane and the processes exit. No engine residency.
 *
 * <p><strong>Lanes.</strong> One worker compiles one module at a time — the wire is a strict
 * {@code READY} / {@code COMPILE} / {@code RESULT} handshake with a single in-flight item, which is
 * what keeps {@link Session}'s diagnostics and transcript attributable to one module. Concurrency
 * comes from running several such workers against one shared queue rather than from multiplexing
 * one worker, so the protocol is untouched. A lane that dies puts the item it was running back
 * once, for another lane; a second death fails it. Lanes start on demand. How many may exist at
 * once is one engine-wide budget, shared by every
 * job: cores, and how many lane leases fit. An idle lane exits when a fork is waiting for memory
 * or another job needs the lane, and the next compile starts one again.
 */
public final class JavaCompilerHost {

    /**
     * Pools keyed by job, by the JDK their workers run on, by the environment the workers start
     * with, by the {@link WorkerHeap} they start with and by the module's own {@code -J} flags: one
     * job may compile modules at two levels, a module that opted into {@code [env] inherit} must not
     * share a resident worker with one that did not, a module whose classpath needs a larger heap
     * gets a worker started with one, and a JVM flag a module asked for is on its worker's command
     * line, which a running worker cannot be given.
     */
    private static final ConcurrentHashMap<String, Lanes> POOLS = new ConcurrentHashMap<>();

    private static final AtomicLong EPHEMERAL = new AtomicLong(-1L);

    /** Monotonic readings: a work item's queue wait, and the teardown join deadline. */
    private static final Clock CLOCK = Clock.SYSTEM;

    /** Live lanes across every job. */
    private static final AtomicInteger ENGINE_LANES = new AtomicInteger();

    /** Every engine-wide pool, including ones a test built by hand. */
    private static final Set<Lanes> ENGINE_POOLS = ConcurrentHashMap.newKeySet();

    private JavaCompilerHost() {}

    /**
     * Bind this thread to a job-scoped Zinc worker. Reuses the current {@link JobWorkers} request
     * when one is open (build). Otherwise opens an ephemeral scope ({@code jk explain} / forecast).
     */
    public static Scope open() {
        Long id = JobWorkers.currentRequestId();
        boolean ephemeral = false;
        if (id == null) {
            id = EPHEMERAL.getAndDecrement();
            JobWorkers.open(id);
            ephemeral = true;
        }
        return new Scope(id, ephemeral);
    }

    /** Send {@code DONE} to every lane and drop the pool for {@code requestId}. Process kill is {@link JobWorkers}. */
    public static void end(long requestId) {
        String prefix = requestId + "|";
        for (String key : List.copyOf(POOLS.keySet())) {
            if (!key.startsWith(prefix)) continue;
            Lanes pool = POOLS.remove(key);
            if (pool != null) pool.close();
        }
    }

    static ForkedJavac.Result compile(ForkedJavac.Request req) {
        Long id = JobWorkers.currentRequestId();
        if (id == null) return ForkedJavac.oneshot(req);
        Long heap = WorkerHeap.forRequest(req);
        return pool(id, req, heap).submit(CompileWork.compile(req, heap));
    }

    static ForkedJavac.Plan plan(ForkedJavac.Request req) {
        Long id = JobWorkers.currentRequestId();
        if (id == null) return ForkedJavac.oneshotPlan(req);
        Long heap = WorkerHeap.forRequest(req);
        return pool(id, req, heap).submitPlan(CompileWork.plan(req, heap));
    }

    private static Lanes pool(long id, ForkedJavac.Request req, @Nullable Long heapBytes) {
        Path home = ForkedJavac.workerJavaHome(req);
        String key = id + "|" + home + "|" + req.env().fingerprint() + "|" + (heapBytes == null ? "" : heapBytes) + "|"
                + String.join(" ", req.jvmArgs());
        return POOLS.computeIfAbsent(key, k -> new Lanes(id, req, home, heapBytes));
    }

    /**
     * The worker compiling {@code failed} ran out of heap: compile it once more on a worker started
     * with {@code heapBytes}, and let that attempt's outcome be the caller's. The retry remembers the
     * heap that failed, so a second exhaustion names both.
     */
    private static void retryWithHeap(long id, CompileWork failed, long heapBytes) {
        ForkedJavac.Request req = Objects.requireNonNull(failed.req, "a retried item carries its request");
        CompileWork again = failed.plan ? CompileWork.plan(req, heapBytes) : CompileWork.compile(req, heapBytes);
        again.previousHeapBytes = failed.heapBytes;
        again.compile.whenComplete((r, e) -> {
            if (e != null) failed.compile.completeExceptionally(e);
            else failed.compile.complete(r);
        });
        again.forecast.whenComplete((r, e) -> {
            if (e != null) failed.forecast.completeExceptionally(e);
            else failed.forecast.complete(r);
        });
        pool(id, req, heapBytes).enqueue(again);
    }

    /** Test seam: live lanes for {@code requestId} across its pools, or 0 when the job has none. */
    static int laneCount(long requestId) {
        String prefix = requestId + "|";
        int lanes = 0;
        for (var e : POOLS.entrySet()) {
            if (e.getKey().startsWith(prefix)) lanes += e.getValue().liveLanes();
        }
        return lanes;
    }

    /** Surface a worker failure to the caller as the cause it actually was, not as a wrapper. */
    private static RuntimeException unwrap(Exception e) {
        Throwable c = e.getCause() == null ? e : e.getCause();
        if (c instanceof RuntimeException re) return re;
        if (c instanceof IOException io) return new UncheckedIOException(io);
        return new RuntimeException(c);
    }

    /**
     * Lanes the engine may run at once, shared by every job: the core count, and how many leases of
     * one lane's heap fit in the worker budget. A job alone can use all of them. {@code
     * JK_COMPILE_LANES} overrides for measurement.
     */
    static int laneBudget() {
        return laneBudget(WorkerLeases.engine());
    }

    /** As {@link #laneBudget()}, measured against {@code leases}. */
    static int laneBudget(WorkerLeases.Ledger leases) {
        int override = laneBudgetForTests > 0 ? laneBudgetForTests : envLanes();
        if (override > 0) return override;
        int cores = Math.max(1, AvailableCpus.count());
        HeapPlan.Plan plan = JvmOptions.processHeapPlan();
        long xmx = plan != null && plan.xmxBytes() > 0 ? plan.xmxBytes() : WorkerHeap.BASE_BYTES;
        long lease = Math.max(1, WorkerLeases.jvmLease(xmx));
        int byMemory = (int) Math.max(1, leases.capacityBytes() / lease);
        return Math.max(1, Math.min(cores, byMemory));
    }

    /** Reserve one engine-wide lane, or false when the budget is already taken. */
    static boolean tryTakeLane() {
        return tryTakeLane(WorkerLeases.engine());
    }

    /** As {@link #tryTakeLane()}, with {@code leases} sizing the cap. */
    static boolean tryTakeLane(WorkerLeases.Ledger leases) {
        int cap = laneBudget(leases);
        while (true) {
            int now = ENGINE_LANES.get();
            if (now >= cap) return false;
            if (ENGINE_LANES.compareAndSet(now, now + 1)) return true;
        }
    }

    /** Return a lane reserved by {@link #tryTakeLane}. */
    static void releaseLane() {
        ENGINE_LANES.updateAndGet(n -> Math.max(0, n - 1));
    }

    /**
     * An idle lane should exit: a fork is waiting for memory, or the lane budget is full while
     * some pool still has work queued.
     */
    static boolean shouldShedIdleLane() {
        return shouldShedIdleLane(WorkerLeases.engine());
    }

    /** As {@link #shouldShedIdleLane()}, watching {@code leases}'s queue. */
    static boolean shouldShedIdleLane(WorkerLeases.Ledger leases) {
        if (leases.queued() > 0) return true;
        if (ENGINE_LANES.get() < laneBudget(leases)) return false;
        for (Lanes pool : ENGINE_POOLS) {
            if (pool.queued() > 0) return true;
        }
        return false;
    }

    /** Pools with queued work try to grow after a lane exits. */
    static void nudgePools() {
        for (Lanes pool : ENGINE_POOLS) pool.grow();
    }

    /** Test seam: drop the engine-wide lane count. Pools must already be closed. */
    static void resetLaneCountForTests() {
        ENGINE_LANES.set(0);
        ENGINE_POOLS.clear();
        onIdleLeaveGap = null;
    }

    /**
     * Test seam: runs without the pool lock after an idle lane has noticed it should leave and
     * before that decision is committed. Assigning work from here keeps the lane.
     */
    static volatile @Nullable Runnable onIdleLeaveGap;

    static void onIdleLeaveGapForTests(@Nullable Runnable hook) {
        onIdleLeaveGap = hook;
    }

    private static volatile int laneBudgetForTests;

    /**
     * Test seam: pin {@link #laneBudget()} to {@code lanes} for pools created from now on; {@code 0}
     * clears it. The only other override is the {@code JK_COMPILE_LANES} environment variable, which
     * a test cannot set.
     */
    static void overrideLaneBudgetForTests(int lanes) {
        laneBudgetForTests = Math.max(0, lanes);
    }

    /** {@code JK_COMPILE_LANES}, or 0 when unset or not a positive integer. */
    private static int envLanes() {
        String v = System.getenv("JK_COMPILE_LANES");
        if (v == null || v.isBlank()) return 0;
        try {
            int n = Integer.parseInt(v.trim());
            return Math.max(0, n);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** {@link AutoCloseable} job/explain scope. */
    public static final class Scope implements AutoCloseable {
        private final long id;
        private final boolean ephemeral;

        private Scope(long id, boolean ephemeral) {
            this.id = id;
            this.ephemeral = ephemeral;
        }

        public long id() {
            return id;
        }

        @Override
        public void close() {
            if (!ephemeral) return;
            end(id);
            JobWorkers.shutdownForRequest(id, 0L);
            JobWorkers.close();
        }
    }

    /** How a pool starts a lane. Production forks a Zinc worker JVM; tests substitute a body. */
    @FunctionalInterface
    interface LaneStarter {
        Session start(Lanes owner, int index);
    }

    /** What a lane's io thread runs. Production converses with the worker until it exits. */
    @FunctionalInterface
    interface LaneBody {
        void run(Session self) throws Exception;
    }

    /** Writes the spec file a dispatch hands the worker. Production is {@link ForkedJavac#writeSpec}. */
    @FunctionalInterface
    interface SpecFile {
        Path write(ForkedJavac.Request req) throws IOException;
    }

    /** Compiles an item again on a worker with {@code heapBytes} after its worker ran out of heap. */
    @FunctionalInterface
    interface HeapRetry {
        void retry(CompileWork failed, long heapBytes);
    }

    /**
     * The job's worker pool: one queue, N {@link Session} lanes draining it.
     *
     * <p><strong>Growth is demand-driven.</strong> A lane is a JVM start plus a Zinc warm-up, so a
     * job that only ever has one module ready never pays for a second one. {@link #grow} adds a lane
     * only when the backlog exceeds the lanes that could take it, and never past {@link
     * #laneBudget}.
     *
     * <p><strong>A dead lane is not a dead pool.</strong> The item it was running goes back on the
     * queue once, for another lane; a second death fails it. Queued items stay queued. Only when
     * the last lane goes, other than one that left to free memory, does the queue drain-fail,
     * because at that point nothing will ever take those items. That holds after {@link #close} too:
     * job teardown kills the workers <em>before</em> it calls {@code end()}, so the last lane
     * usually dies with the pool already closed, and an item still queued then has no other taker.
     */
    static final class Lanes {

        private final BlockingQueue<CompileWork> queue = new LinkedBlockingQueue<>();
        private final int budget;
        private final boolean engineWide;
        private final WorkerLeases.Ledger leases;
        private final LaneStarter starter;
        final SpecFile specs;
        final HeapRetry retry;

        /** Live lanes. Guarded by {@code this}; {@link Session#working} is read without the lock. */
        private final List<Session> lanes = new ArrayList<>();

        private boolean closed;

        Lanes(long id, ForkedJavac.Request template, Path workerJavaHome, @Nullable Long heapBytes) {
            this(
                    laneBudget(),
                    (owner, index) ->
                            new Session(owner, id, index, self -> self.converse(template, workerJavaHome, heapBytes)),
                    ForkedJavac::writeSpec,
                    (failed, bigger) -> retryWithHeap(id, failed, bigger),
                    true);
        }

        /** Test seam: a pool whose lanes run {@code starter}'s body instead of forking a worker. */
        Lanes(int budget, LaneStarter starter, SpecFile specs) {
            this(budget, starter, specs, (failed, bigger) -> {
                IOException none = new IOException("no pool to retry on");
                failed.compile.completeExceptionally(none);
                failed.forecast.completeExceptionally(none);
            });
        }

        /** Test seam: as above, with the pool's answer to a worker that ran out of heap. */
        Lanes(int budget, LaneStarter starter, SpecFile specs, HeapRetry retry) {
            this(budget, starter, specs, retry, false);
        }

        /**
         * {@code engineWide} counts the lane against the engine budget and exits it when that
         * budget, or a waiting fork, needs it back. {@code leases} is the queue that waiting fork
         * is counted on; production passes {@link WorkerLeases#engine()}.
         */
        Lanes(
                int budget,
                LaneStarter starter,
                SpecFile specs,
                HeapRetry retry,
                boolean engineWide,
                WorkerLeases.Ledger leases) {
            this.budget = budget;
            this.engineWide = engineWide;
            this.leases = leases;
            this.starter = starter;
            this.specs = specs;
            this.retry = retry;
            if (engineWide) ENGINE_POOLS.add(this);
        }

        /** As above, leasing from {@link WorkerLeases#engine()}. */
        Lanes(int budget, LaneStarter starter, SpecFile specs, HeapRetry retry, boolean engineWide) {
            this(budget, starter, specs, retry, engineWide, WorkerLeases.engine());
        }

        ForkedJavac.Result submit(CompileWork w) {
            enqueue(w);
            try {
                return w.compile.get();
            } catch (InterruptedException e) {
                throw interrupted(w, e);
            } catch (Exception e) {
                throw unwrap(e);
            }
        }

        ForkedJavac.Plan submitPlan(CompileWork w) {
            enqueue(w);
            try {
                return w.forecast.get();
            } catch (InterruptedException e) {
                throw interrupted(w, e);
            } catch (Exception e) {
                throw unwrap(e);
            }
        }

        /**
         * The submitter was interrupted (job cancel). Take the item back off the queue so no lane
         * compiles for a caller that has left, keep the interrupt, and say what happened.
         */
        private RuntimeException interrupted(CompileWork w, InterruptedException e) {
            Thread.currentThread().interrupt();
            queue.remove(w);
            w.compile.completeExceptionally(e);
            w.forecast.completeExceptionally(e);
            return new UncheckedIOException(new InterruptedIOException("compile interrupted while queued"));
        }

        /**
         * Enqueue, then make sure a lane exists that will take it. The post-add emptiness check is
         * the same race {@code Session}'s death path guards from the other side: if the last lane
         * died between the add and now, its drain has already run and would never see this item,
         * hanging {@code compile.get} forever.
         */
        void enqueue(CompileWork w) {
            w.enqueuedNanos = CLOCK.nanos();
            // The add shares the pool lock with the idle-leave check, so a lane cannot observe an
            // empty queue and commit to leaving while this item is already on it.
            synchronized (this) {
                queue.add(w);
            }
            if (!grow()) drainFailQueued(new IOException("zinc worker exited"));
        }

        /**
         * Start a lane when the backlog outruns the lanes that could absorb it. Returns whether the
         * pool has a lane that will take the queue — false once the job is closing, which is the
         * caller's cue that nothing will drain it: a post-close item would otherwise sit behind the
         * POISONs forever, even with lanes still alive to take those.
         */
        private synchronized boolean grow() {
            if (closed) return false;
            lanes.removeIf(lane -> !lane.alive());
            int free = 0;
            for (Session lane : lanes) {
                if (!lane.working()) free++;
            }
            int cap = engineWide ? laneBudget(leases) : budget;
            if (free < queue.size() && lanes.size() < cap) {
                // The engine budget is full. Leave the item queued; a lane that exits nudges us.
                if (engineWide && !tryTakeLane(leases)) return true;
                try {
                    lanes.add(starter.start(this, lanes.size()));
                } catch (RuntimeException e) {
                    if (engineWide) releaseLane();
                    throw e;
                }
            }
            return !lanes.isEmpty();
        }

        /** An idle engine-wide lane exits when another fork needs its memory or its lane slot. */
        synchronized boolean idleShouldExit() {
            return engineWide && !closed && shouldShedIdleLane(leases);
        }

        /** Under the pool lock: this idle lane should leave, and its queue has nothing for it. */
        synchronized boolean readyToLeave() {
            return idleShouldExit() && queue.isEmpty();
        }

        /**
         * Under the pool lock: commit to leaving only while the queue is still empty. Work that
         * arrived since the idle look is taken instead — a lane is never both given an item and
         * told to exit.
         */
        synchronized boolean commitLeave(Session lane) {
            if (lane.dead || !idleShouldExit() || !queue.isEmpty()) return false;
            lane.shedding = true;
            return true;
        }

        /** Live lanes, dead ones pruned. */
        synchronized int liveLanes() {
            lanes.removeIf(lane -> !lane.alive());
            return lanes.size();
        }

        /** Test seam: items waiting for a lane. */
        int queued() {
            return queue.size();
        }

        /** Test seam: whether {@link #close} has begun. */
        synchronized boolean closing() {
            return closed;
        }

        /**
         * A lane's worker exited. Drop it, and if it was the last one there is no longer anything
         * that will take the queue — fail what is on it rather than let those callers block. Closed
         * or not: after {@code close()} the lanes are still the only takers, and job teardown kills
         * them before it closes the pool, so "last lane dies after close" is the common order.
         */
        void laneDied(Session lane, Throwable cause) {
            boolean last;
            boolean wide;
            boolean shed;
            synchronized (this) {
                lanes.remove(lane);
                last = lanes.isEmpty();
                wide = engineWide;
                shed = lane.shedding;
            }
            if (wide) {
                releaseLane();
                nudgePools();
            }
            // A lane that left to free memory is not a crash. Work queued as it left stays queued
            // and the nudge above starts a replacement; failing it here dropped a compile that
            // arrived between the idle poll and the exit.
            if (last && !shed) drainFailQueued(cause);
        }

        /**
         * Fail every queued (not-yet-dispatched) Work. Safe from any thread — it only polls the
         * concurrent queue and completes futures, both idempotent — and never touches a lane's
         * {@code slot} or {@code inflight}. POISONs that were polled off go back on: a lane still
         * alive to take one must still get its {@code DONE}.
         */
        private void drainFailQueued(Throwable e) {
            int poisons = 0;
            CompileWork w;
            while ((w = queue.poll()) != null) {
                if (w == CompileWork.POISON) {
                    poisons++;
                    continue;
                }
                w.compile.completeExceptionally(e);
                w.forecast.completeExceptionally(e);
            }
            for (int i = 0; i < poisons; i++) queue.add(CompileWork.POISON);
        }

        /**
         * One {@code DONE} per lane, then wait for the workers to exit. The join budget is the
         * pool's, not each lane's: a wedged worker must not multiply the teardown wait by the lane
         * count. The lanes stay registered through the join so {@link #laneDied} still knows when
         * the last one goes; whatever is still queued after the join has no taker and is failed.
         */
        void close() {
            ENGINE_POOLS.remove(this);
            List<Session> live;
            synchronized (this) {
                closed = true;
                live = List.copyOf(lanes);
            }
            for (int i = 0; i < live.size(); i++) queue.add(CompileWork.POISON);
            long deadline = CLOCK.nanos() + TimeUnit.SECONDS.toNanos(15);
            for (Session lane : live) {
                lane.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - CLOCK.nanos())));
            }
            drainFailQueued(new IOException("zinc worker pool closed"));
        }
    }

    /**
     * One worker JVM, draining its pool's shared queue one item at a time.
     *
     * <p>Two threads share a lane. The {@code io} thread runs the body and outlives the worker by
     * exactly as long as it takes to notice the exit. The worker's pump thread delivers every
     * protocol line, so it is the pump — not {@code io} — that blocks in {@link #takeNext} and
     * writes {@code slot}, {@code busy} and {@code inflight}. The pump may still be parked on the
     * queue after the worker has died; {@link #takeNext} checks {@code dead} so an item it takes
     * then goes back to the pool instead of to a worker that is gone.
     */
    static final class Session {
        private final Lanes owner;
        private final long requestId;
        private final Thread io;
        // Atomic because the pump thread that dispatches an item and the io thread that notices
        // the worker's death both try to take it out: whoever swaps it to null owns its fate.
        private final AtomicReference<@Nullable CompileWork> inflight = new AtomicReference<>();
        // Set the instant an item leaves the queue for this lane, before the spec write; inflight is
        // set only once the command is on the wire. grow() reads this one: a lane that has taken an
        // item is not free capacity.
        private volatile boolean busy;
        private volatile boolean dead;
        // Bounded record of the worker's non-protocol lines, surfaced on a crash; reset at each
        // dispatch so it describes the work item that died, not the worker's first breath.
        private final WorkerTranscript transcript = new WorkerTranscript();

        /** Set when this lane leaves on purpose so another fork can have its memory. */
        volatile boolean shedding;

        Session(Lanes owner, long id, int lane, LaneBody body) {
            this.owner = owner;
            this.requestId = id;
            // The pool belongs to one job. A lane is often grown by another job's thread — an idle
            // lane exiting nudges every pool — so the body binds this job before it forks, rather
            // than inheriting whoever happened to start the thread.
            io = SessionContext.startVirtual("jk-zinc-host-" + id + "-" + lane, () -> drive(body));
        }

        boolean alive() {
            return !dead && io.isAlive();
        }

        /**
         * Whether this lane holds an item — taken off the queue, or already on the wire. A lane
         * that is starting up, or blocked in {@code take()}, counts as free.
         */
        boolean working() {
            return busy;
        }

        void join(long millis) {
            try {
                io.join(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** The lane's whole life: run the body, then tell the pool this lane is gone — once. */
        private void drive(LaneBody body) {
            Long previous = JobWorkers.bind(requestId);
            Throwable cause = null;
            try {
                body.run(this);
            } catch (Exception e) {
                cause = e;
            } finally {
                try {
                    dead = true;
                    failAll(cause != null ? cause : new IOException("zinc worker exited"));
                } finally {
                    JobWorkers.restore(previous);
                }
            }
        }

        /**
         * Fork the worker JVM on {@code hostJavaHome} and drive its READY / COMPILE / RESULT
         * conversation until it exits.
         */
        private void converse(ForkedJavac.Request template, Path hostJavaHome, @Nullable Long heapBytes)
                throws Exception {
            String workerCp = ForkedJavac.workerClasspath(template);
            List<String> jvmFlags = ForkedJavac.workerJvmFlags(
                    WorkerAotCache.flags(
                            "java-compiler",
                            hostJavaHome,
                            workerCp,
                            ForkedJavac.novelJvmArgs(template),
                            (aotOutput, scratch) ->
                                    ForkedJavac.trainerCommand(template, workerCp, hostJavaHome, aotOutput, scratch)),
                    heapBytes,
                    template.jvmArgs());
            List<String> command = PluginLoader.command(hostJavaHome, workerCp, jvmFlags, List.of("--pull"));
            int exit = new PluginClient(ForkedJavac.PREFIX)
                    .passthrough(this::output)
                    .converseNoSlot(
                            command, template.env().withJavaHome(hostJavaHome), (json, convo) -> onLine(json, convo));
            // A lane that left because another fork needed its memory has no compile in flight.
            // The process exit is the shutdown we asked for, even when the kill lands first.
            if (exit != 0 && !(shedding && inflight.get() == null)) {
                throw new IOException("zinc worker " + WorkerContainment.failure(exit, "exited with status " + exit));
            }
        }

        /** A non-protocol line the worker wrote: kept for the report should this item's worker die. */
        void output(String line) {
            transcript.record(line);
        }

        /** How long a parked lane goes between looks at whether its worker is still there. */
        private static final long TAKE_POLL_MS = 200;

        /**
         * Block for the pool's next item; the lane is busy from the moment it has one. A lane whose
         * worker has died hands anything it takes straight back to the pool and reports {@link
         * CompileWork#POISON} so the pump unwinds: an item dispatched to a dead worker would never complete.
         */
        CompileWork takeNext() throws InterruptedException {
            while (true) {
                CompileWork next = pollUnderLock();
                if (next != null) return next;
                if (dead) return CompileWork.POISON;
                if (leaveForMemory()) return CompileWork.POISON;
                next = owner.queue.poll(TAKE_POLL_MS, TimeUnit.MILLISECONDS);
                if (next == null) continue;
                synchronized (owner) {
                    if (dead) {
                        owner.queue.add(next);
                        return CompileWork.POISON;
                    }
                    if (next != CompileWork.POISON) busy = true;
                    return next;
                }
            }
        }

        /**
         * The next queued item, taken under the same lock that decides a leave. {@code null} when
         * the queue is empty. {@link CompileWork#POISON} when this lane is dead or the pool is
         * closing the lane.
         */
        private @Nullable CompileWork pollUnderLock() {
            synchronized (owner) {
                if (dead) return CompileWork.POISON;
                CompileWork next = owner.queue.poll();
                if (next == null || next == CompileWork.POISON) return next;
                busy = true;
                return next;
            }
        }

        /**
         * Leave only when, under the pool lock, the queue is still empty. A test hook (and any
         * enqueue that races it) runs between the two looks, and work that arrived is taken on the
         * next loop instead of being abandoned on a lane that is exiting.
         */
        private boolean leaveForMemory() {
            if (!owner.readyToLeave()) return false;
            Runnable gap = onIdleLeaveGap;
            if (gap != null) gap.run();
            return owner.commitLeave(this);
        }

        void onLine(String json, PluginProcess.Conversation convo) {
            String t = Jsonl.str(json, PluginProtocol.T);
            if (PluginProtocol.READY.equals(t)) {
                dispatchNext(convo);
                return;
            }
            CompileWork w = inflight.get();
            if (w == null) return;
            if (PluginProtocol.DIAGNOSTIC.equals(t)) {
                // Through WorkerDiagnostics, never a bare `new Diagnostic(...)`: downstream
                // consumers scrape the MESSAGE for the locus (PlannerCompile forwards
                // describe(), which is message-only), so the worker's file/line has to be
                // folded into a javac-style header here or the error never names its file.
                w.diagnostics.add(WorkerDiagnostics.located(
                        Jsonl.str(json, "sev"),
                        Jsonl.str(json, "file"),
                        Jsonl.longValue(json, "line", 0),
                        Jsonl.longValue(json, "col", 0),
                        Jsonl.str(json, "msg"),
                        Jsonl.str(json, "key")));
                return;
            }
            if (PluginProtocol.PROVENANCE.equals(t)) {
                String genStr = Jsonl.str(json, "gen");
                if (genStr == null) return;
                Set<Path> origins = new TreeSet<>();
                for (String s : Jsonl.strArray(json, "src")) origins.add(Path.of(s));
                w.generated.put(Path.of(genStr), origins);
                return;
            }
            if (PluginProtocol.RESULT.equals(t)) {
                w.status = Jsonl.str(json, "status");
                w.outcome = Jsonl.str(json, "outcome");
                w.reason = Jsonl.str(json, "reason");
                for (String s : Jsonl.strArray(json, "compiled")) w.compiledSources.add(Path.of(s));
                for (String s : Jsonl.strArray(json, "whys")) w.whys.add(s);
                // Free the lane before waking the submitter: its next module may enqueue at once,
                // and a lane still marked busy would make grow() start a JVM this lane could take.
                idle();
                complete(w);
                return;
            }
            if (PluginProtocol.ERROR.equals(t)) {
                IOException err = new IOException(Jsonl.str(json, "message"));
                deleteSpec(w);
                idle();
                w.compile.completeExceptionally(err);
                w.forecast.completeExceptionally(err);
            }
        }

        /** The exchange is over: no item, not busy. */
        private void idle() {
            inflight.set(null);
            busy = false;
        }

        /**
         * The worker is at READY: hand it the next item, or {@code DONE}. A spec that cannot be
         * written fails only its own item and the loop takes the next one — the worker is still
         * waiting for a command, and returning without sending one would leave it, and this lane,
         * waiting on each other for the rest of the job.
         */
        private void dispatchNext(PluginProcess.Conversation convo) {
            while (true) {
                CompileWork next;
                try {
                    next = takeNext();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    convo.send("DONE");
                    convo.closeInput();
                    return;
                }
                if (next == CompileWork.POISON || next.req == null) {
                    convo.send("DONE");
                    convo.closeInput();
                    return;
                }
                try {
                    next.waitNanos = CLOCK.nanos() - next.enqueuedNanos;
                    next.spec = owner.specs.write(next.req);
                    transcript.reset();
                    inflight.set(next);
                    // The worker may have died during the spec write. inflight is published before
                    // this read and failAll sets dead before its read, so one of the two sides
                    // always sees the other; the swap decides which one owns the item.
                    if (dead) {
                        if (inflight.compareAndSet(next, null)) {
                            deleteSpec(next);
                            owner.enqueue(next);
                        }
                        busy = false;
                        convo.send("DONE");
                        convo.closeInput();
                        return;
                    }
                    convo.send((next.plan ? "PLAN " : "COMPILE ") + next.spec.toAbsolutePath());
                    return;
                } catch (IOException e) {
                    next.compile.completeExceptionally(e);
                    next.forecast.completeExceptionally(e);
                    busy = false;
                }
            }
        }

        /** Delete a work item's spec temp file on every terminal path. */
        private static void deleteSpec(CompileWork w) {
            if (w == null || w.spec == null) return;
            try {
                Files.deleteIfExists(w.spec);
            } catch (IOException ignored) {
                // temp spec — best effort
            }
        }

        private static long waitMillis(CompileWork w) {
            return TimeUnit.NANOSECONDS.toMillis(Math.max(0, w.waitNanos));
        }

        private static void complete(CompileWork w) {
            deleteSpec(w);
            if (w.plan) {
                boolean full = "full".equalsIgnoreCase(w.outcome);
                List<ForkedJavac.Invalidation> items = new ArrayList<>();
                for (int i = 0; i < w.compiledSources.size(); i++) {
                    String why = i < w.whys.size() ? w.whys.get(i) : (w.reason == null ? "" : w.reason);
                    items.add(new ForkedJavac.Invalidation(w.compiledSources.get(i), why));
                }
                w.forecast.complete(new ForkedJavac.Plan(full, w.reason == null ? "" : w.reason, items));
                w.compile.complete(new ForkedJavac.Result(true, List.of(), Map.of(), List.of(), waitMillis(w)));
            } else {
                boolean success = "OK".equals(w.status);
                w.compile.complete(
                        new ForkedJavac.Result(success, w.diagnostics, w.generated, w.compiledSources, waitMillis(w)));
                w.forecast.complete(new ForkedJavac.Plan(false, "", List.of()));
            }
        }

        /**
         * This lane's worker is gone. The item it was running is tried once on another lane when
         * this job is still alive; the pool hears about the death either way.
         */
        private void failAll(Throwable e) {
            CompileWork cur = inflight.getAndSet(null);
            busy = false;
            // Drop the lane before putting the item back, so a last-lane drain cannot fail the
            // retry it has not been given yet.
            owner.laneDied(this, e);
            if (cur != null) {
                deleteSpec(cur);
                settle(cur, e);
            }
        }

        /**
         * The item the dead worker was compiling. A worker that ran out of heap is answered with a
         * retry on twice the heap, once. Any other death, while this job is still alive, goes back
         * on the queue once; the second death fails it with the worker's output attached.
         */
        private void settle(CompileWork cur, Throwable e) {
            String output = transcript.render();
            Long heap = cur.heapBytes;
            if (heap == null || !WorkerHeap.outOfMemory(output)) {
                if (!cur.revived && !owner.closing() && !JobWorkers.ended(requestId)) {
                    cur.revived = true;
                    cur.clearAttempt();
                    owner.enqueue(cur);
                    return;
                }
                fail(cur, withWorkerTail(e));
                return;
            }
            String label = cur.req == null ? "" : cur.req.label();
            if (cur.previousHeapBytes != null) {
                fail(cur, WorkerHeap.exhausted(label, cur.previousHeapBytes, heap, output));
                return;
            }
            Long bigger = WorkerHeap.grown(heap);
            if (bigger == null) {
                fail(cur, WorkerHeap.exhausted(label, heap, null, output));
                return;
            }
            owner.retry.retry(cur, bigger);
        }

        private static void fail(CompileWork w, Throwable cause) {
            w.compile.completeExceptionally(cause);
            w.forecast.completeExceptionally(cause);
        }

        /**
         * Attach the tail of the worker's non-protocol output (stack trace / OOM banner / spec-parse
         * error) to a worker-death exception — otherwise the engine reports only "zinc worker exited"
         * with no cause.
         */
        private Throwable withWorkerTail(Throwable e) {
            if (transcript.isEmpty()) return e;
            return new IOException(e.getMessage() + "\n--- zinc worker output ---\n" + transcript.render(), e);
        }
    }
}
