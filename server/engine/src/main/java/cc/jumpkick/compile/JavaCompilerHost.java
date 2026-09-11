// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.engine.plugin.HeapPlan;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.engine.plugin.PluginAot;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.engine.plugin.PluginProcess;
import cc.jumpkick.engine.plugin.PluginSlots;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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
 * what keeps {@link Session}'s diagnostics, transcript and {@link PluginSlots} lease attributable to
 * one module. Concurrency comes from running several such workers against one shared queue rather
 * than from multiplexing one worker, so the protocol is untouched and a lane that dies takes only
 * its own item with it. Lanes start on demand and are reused for the rest of the job.
 */
public final class JavaCompilerHost {

    private static final ConcurrentHashMap<Long, Lanes> POOLS = new ConcurrentHashMap<>();
    private static final AtomicLong EPHEMERAL = new AtomicLong(-1L);

    /** Monotonic readings: a work item's queue wait, and the teardown join deadline. */
    private static final Clock CLOCK = Clock.SYSTEM;

    /**
     * Ceiling on lanes per job when the memory plan allows more. Each lane is a resident JVM with a
     * full compiler heap, so the useful range is bounded by the build graph's width long before it
     * is bounded by cores: past a handful of lanes the extra JVMs cost cold starts and RSS to sit
     * idle behind a dependency edge. {@code JK_COMPILE_LANES} overrides for measurement.
     */
    static final int DEFAULT_LANE_CAP = 4;

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
        Lanes pool = POOLS.remove(requestId);
        if (pool != null) pool.close();
    }

    static ForkedJavac.Result compile(ForkedJavac.Request req) {
        Long id = JobWorkers.currentRequestId();
        if (id == null) return ForkedJavac.oneshot(req);
        return pool(id, req).submit(Work.compile(req));
    }

    static ForkedJavac.Plan plan(ForkedJavac.Request req) {
        Long id = JobWorkers.currentRequestId();
        if (id == null) return ForkedJavac.oneshotPlan(req);
        return pool(id, req).submitPlan(Work.plan(req));
    }

    private static Lanes pool(long id, ForkedJavac.Request req) {
        return POOLS.computeIfAbsent(id, k -> new Lanes(k, req));
    }

    /** Test seam: live lanes for {@code requestId}, or 0 when the job has no pool. */
    static int laneCount(long requestId) {
        Lanes pool = POOLS.get(requestId);
        return pool == null ? 0 : pool.liveLanes();
    }

    /** Surface a worker failure to the caller as the cause it actually was, not as a wrapper. */
    private static RuntimeException unwrap(Exception e) {
        Throwable c = e.getCause() == null ? e : e.getCause();
        if (c instanceof RuntimeException re) return re;
        if (c instanceof IOException io) return new UncheckedIOException(io);
        return new RuntimeException(c);
    }

    /**
     * Lanes this job may run at once: the memory plan's worker budget, capped at {@link
     * #DEFAULT_LANE_CAP}. Falls back to the core count when no plan has been applied ({@code jk
     * explain}, tests), which the cap then dominates anyway.
     */
    static int laneBudget() {
        int override = laneBudgetForTests > 0 ? laneBudgetForTests : envLanes();
        if (override > 0) return override;
        HeapPlan.Plan plan = JvmOptions.processHeapPlan();
        int budget = plan != null ? plan.parallelism() : Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(budget, DEFAULT_LANE_CAP));
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

    static final class Work {
        final ForkedJavac.@Nullable Request req;
        final boolean plan;
        final CompletableFuture<ForkedJavac.Result> compile = new CompletableFuture<>();
        final CompletableFuture<ForkedJavac.Plan> forecast = new CompletableFuture<>();
        final List<CompileResult.Diagnostic> diagnostics = new ArrayList<>();
        final Map<Path, Set<Path>> generated = new TreeMap<>();
        final List<Path> compiledSources = new ArrayList<>();
        final List<String> whys = new ArrayList<>();

        @Nullable
        Path spec;

        @Nullable
        String status;

        @Nullable
        String outcome;

        @Nullable
        String reason;
        /** nanoTime at enqueue and the queue wait measured at dispatch — the step's wait, not its work. */
        long enqueuedNanos;

        long waitNanos;

        private Work(ForkedJavac.@Nullable Request req, boolean plan) {
            this.req = req;
            this.plan = plan;
        }

        static Work compile(ForkedJavac.Request req) {
            return new Work(req, false);
        }

        static Work plan(ForkedJavac.Request req) {
            return new Work(req, true);
        }

        static final Work POISON = new Work(null, false);
    }

    /**
     * The job's worker pool: one queue, N {@link Session} lanes draining it.
     *
     * <p><strong>Growth is demand-driven.</strong> A lane is a JVM start plus a Zinc warm-up, so a
     * job that only ever has one module ready never pays for a second one. {@link #grow} adds a lane
     * only when the backlog exceeds the lanes that could take it, and never past {@link
     * #laneBudget}.
     *
     * <p><strong>A dead lane is not a dead pool.</strong> Its in-flight item fails — that work was
     * being compiled by the process that died and cannot be attributed elsewhere — but queued items
     * stay queued for the surviving lanes. Only when the last lane goes does the queue drain-fail,
     * because at that point nothing will ever take those items. That holds after {@link #close} too:
     * job teardown kills the workers <em>before</em> it calls {@code end()}, so the last lane
     * usually dies with the pool already closed, and an item still queued then has no other taker.
     */
    static final class Lanes {

        private final BlockingQueue<Work> queue = new LinkedBlockingQueue<>();
        private final int budget;
        private final LaneStarter starter;
        final SpecFile specs;

        /** Live lanes. Guarded by {@code this}; {@link Session#working} is read without the lock. */
        private final List<Session> lanes = new ArrayList<>();

        private boolean closed;

        Lanes(long id, ForkedJavac.Request template) {
            this(
                    laneBudget(),
                    (owner, index) -> new Session(owner, id, index, self -> self.converse(template)),
                    ForkedJavac::writeSpec);
        }

        /** Test seam: a pool whose lanes run {@code starter}'s body instead of forking a worker. */
        Lanes(int budget, LaneStarter starter, SpecFile specs) {
            this.budget = budget;
            this.starter = starter;
            this.specs = specs;
        }

        ForkedJavac.Result submit(Work w) {
            enqueue(w);
            try {
                return w.compile.get();
            } catch (InterruptedException e) {
                throw interrupted(w, e);
            } catch (Exception e) {
                throw unwrap(e);
            }
        }

        ForkedJavac.Plan submitPlan(Work w) {
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
        private RuntimeException interrupted(Work w, InterruptedException e) {
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
        void enqueue(Work w) {
            w.enqueuedNanos = CLOCK.nanos();
            queue.add(w);
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
            if (free < queue.size() && lanes.size() < budget) {
                lanes.add(starter.start(this, lanes.size()));
            }
            return !lanes.isEmpty();
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
            synchronized (this) {
                lanes.remove(lane);
                last = lanes.isEmpty();
            }
            if (last) drainFailQueued(cause);
        }

        /**
         * Fail every queued (not-yet-dispatched) Work. Safe from any thread — it only polls the
         * concurrent queue and completes futures, both idempotent — and never touches a lane's
         * {@code slot} or {@code inflight}. POISONs that were polled off go back on: a lane still
         * alive to take one must still get its {@code DONE}.
         */
        private void drainFailQueued(Throwable e) {
            int poisons = 0;
            Work w;
            while ((w = queue.poll()) != null) {
                if (w == Work.POISON) {
                    poisons++;
                    continue;
                }
                w.compile.completeExceptionally(e);
                w.forecast.completeExceptionally(e);
            }
            for (int i = 0; i < poisons; i++) queue.add(Work.POISON);
        }

        /**
         * One {@code DONE} per lane, then wait for the workers to exit. The join budget is the
         * pool's, not each lane's: a wedged worker must not multiply the teardown wait by the lane
         * count. The lanes stay registered through the join so {@link #laneDied} still knows when
         * the last one goes; whatever is still queued after the join has no taker and is failed.
         */
        void close() {
            List<Session> live;
            synchronized (this) {
                closed = true;
                live = List.copyOf(lanes);
            }
            for (int i = 0; i < live.size(); i++) queue.add(Work.POISON);
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
        private final Thread io;
        // Atomic because the pump thread that dispatches an item and the io thread that notices
        // the worker's death both try to take it out: whoever swaps it to null owns its fate.
        private final AtomicReference<@Nullable Work> inflight = new AtomicReference<>();
        // Set the instant an item leaves the queue for this lane, before the slot wait and the spec
        // write; inflight is set only once the command is on the wire. grow() reads this one:
        // a lane parked in PluginSlots.acquire() holds an item and is not capacity.
        private volatile boolean busy;
        private volatile boolean dead;
        // Held only while a COMPILE/PLAN is in flight, so the resident worker does not pin a
        // PluginSlots permit while idle. A lease is released exactly once, from whichever thread
        // ends the exchange.
        private final AtomicReference<PluginSlots.@Nullable Lease> slot = new AtomicReference<>();
        // Bounded record of the worker's non-protocol lines, surfaced on a crash; reset at each
        // dispatch so it describes the work item that died, not the worker's first breath.
        private final WorkerTranscript transcript = new WorkerTranscript();

        Session(Lanes owner, long id, int lane, LaneBody body) {
            this.owner = owner;
            io = Thread.ofVirtual().name("jk-zinc-host-" + id + "-" + lane).start(() -> drive(body));
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
            Throwable cause = null;
            try {
                body.run(this);
            } catch (Exception e) {
                cause = e;
            } finally {
                dead = true;
                failAll(cause != null ? cause : new IOException("zinc worker exited"));
            }
        }

        /** Fork the worker JVM and drive its READY / COMPILE / RESULT conversation until it exits. */
        private void converse(ForkedJavac.Request template) throws Exception {
            Path hostJavaHome = JavaHomes.runningJavaHome();
            Path javaExe = JdkFingerprint.java(hostJavaHome);
            String workerCp = ForkedJavac.workerClasspath(template);
            List<String> jvmFlags = new ArrayList<>(PluginAot.javaCompilerFlags(
                    hostJavaHome,
                    workerCp,
                    (aotOutput, scratch) ->
                            ForkedJavac.trainerCommand(template, workerCp, hostJavaHome, aotOutput, scratch)));
            jvmFlags.addAll(JvmOptions.batchFlags(1));
            List<String> command = PluginLoader.command(javaExe, workerCp, jvmFlags, List.of("--pull"));
            int exit = new PluginClient(ForkedJavac.PREFIX)
                    .passthrough(transcript::record)
                    .converseNoSlot(command, (json, convo) -> onLine(json, convo));
            if (exit != 0) throw new IOException("zinc worker exited with status " + exit);
        }

        /** How long a parked lane goes between looks at whether its worker is still there. */
        private static final long TAKE_POLL_MS = 200;

        /**
         * Block for the pool's next item; the lane is busy from the moment it has one. A lane whose
         * worker has died hands anything it takes straight back to the pool and reports {@link
         * Work#POISON} so the pump unwinds: an item dispatched to a dead worker would never complete.
         */
        Work takeNext() throws InterruptedException {
            while (true) {
                Work next = owner.queue.poll(TAKE_POLL_MS, TimeUnit.MILLISECONDS);
                if (next == null) {
                    if (dead) return Work.POISON;
                    continue;
                }
                if (dead) {
                    if (next == Work.POISON) owner.queue.add(next);
                    else owner.enqueue(next);
                    return Work.POISON;
                }
                busy = true;
                return next;
            }
        }

        void onLine(String json, PluginProcess.Conversation convo) {
            String t = Jsonl.str(json, PluginProtocol.T);
            if (PluginProtocol.READY.equals(t)) {
                dispatchNext(convo);
                return;
            }
            Work w = inflight.get();
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
                        Jsonl.str(json, "msg")));
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

        /** The exchange is over: no item, not busy, no worker slot held. */
        private void idle() {
            inflight.set(null);
            busy = false;
            releaseSlot();
        }

        /**
         * The worker is at READY: hand it the next item, or {@code DONE}. A spec that cannot be
         * written fails only its own item and the loop takes the next one — the worker is still
         * waiting for a command, and returning without sending one would leave it, and this lane,
         * waiting on each other for the rest of the job.
         */
        private void dispatchNext(PluginProcess.Conversation convo) {
            while (true) {
                Work next;
                try {
                    next = takeNext();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    convo.send("DONE");
                    convo.closeInput();
                    return;
                }
                if (next == Work.POISON || next.req == null) {
                    convo.send("DONE");
                    convo.closeInput();
                    return;
                }
                // Take a worker slot only for the duration of this exchange; released on
                // RESULT/ERROR/failure so an idle session holds none.
                slot.set(PluginSlots.acquire());
                try {
                    next.waitNanos = CLOCK.nanos() - next.enqueuedNanos;
                    next.spec = owner.specs.write(next.req);
                    transcript.reset();
                    inflight.set(next);
                    // The worker may have died during the slot wait or the spec write. inflight is
                    // published before this read and failAll sets dead before its read, so one of
                    // the two sides always sees the other; the swap decides which one owns the item.
                    if (dead) {
                        if (inflight.compareAndSet(next, null)) {
                            deleteSpec(next);
                            owner.enqueue(next);
                        }
                        busy = false;
                        releaseSlot();
                        convo.send("DONE");
                        convo.closeInput();
                        return;
                    }
                    convo.send((next.plan ? "PLAN " : "COMPILE ") + next.spec.toAbsolutePath());
                    return;
                } catch (IOException e) {
                    next.compile.completeExceptionally(e);
                    next.forecast.completeExceptionally(e);
                    releaseSlot();
                }
            }
        }

        /** Return the in-flight worker slot to the pool; a lease is closed once, whoever gets there. */
        private void releaseSlot() {
            PluginSlots.Lease s = slot.getAndSet(null);
            if (s != null) s.close();
        }

        /** Delete a work item's spec temp file on every terminal path. */
        private static void deleteSpec(Work w) {
            if (w == null || w.spec == null) return;
            try {
                Files.deleteIfExists(w.spec);
            } catch (IOException ignored) {
                // temp spec — best effort
            }
        }

        private static long waitMillis(Work w) {
            return TimeUnit.NANOSECONDS.toMillis(Math.max(0, w.waitNanos));
        }

        private static void complete(Work w) {
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
         * This lane's worker is gone. Fail the item it was compiling — that work died with the
         * process and cannot be attributed to another lane — and hand the pool the cause so it can
         * decide whether anything is left to drain the queue.
         */
        private void failAll(Throwable e) {
            releaseSlot();
            Work cur = inflight.getAndSet(null);
            busy = false;
            if (cur != null) {
                deleteSpec(cur);
                Throwable withTail = withWorkerTail(e);
                cur.compile.completeExceptionally(withTail);
                cur.forecast.completeExceptionally(withTail);
            }
            owner.laneDied(this, e);
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
