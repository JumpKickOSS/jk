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
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import java.io.IOException;
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
        int override = envLanes();
        if (override > 0) return override;
        HeapPlan.Plan plan = JvmOptions.processHeapPlan();
        int budget = plan != null ? plan.parallelism() : Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(budget, DEFAULT_LANE_CAP));
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

    private static final class Work {
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
     * because at that point nothing will ever take those items.
     */
    private static final class Lanes {

        private final long id;
        private final ForkedJavac.Request template;
        private final BlockingQueue<Work> queue = new LinkedBlockingQueue<>();
        private final int budget = laneBudget();

        /** Live lanes. Guarded by {@code this}; {@link Session#working} is read without the lock. */
        private final List<Session> lanes = new ArrayList<>();

        private boolean closed;

        Lanes(long id, ForkedJavac.Request template) {
            this.id = id;
            this.template = template;
        }

        ForkedJavac.Result submit(Work w) {
            enqueue(w);
            try {
                return w.compile.get();
            } catch (Exception e) {
                throw unwrap(e);
            }
        }

        ForkedJavac.Plan submitPlan(Work w) {
            enqueue(w);
            try {
                return w.forecast.get();
            } catch (Exception e) {
                throw unwrap(e);
            }
        }

        /**
         * Enqueue, then make sure a lane exists that will take it. The post-add emptiness check is
         * the same race {@code Session}'s death path guards from the other side: if the last lane
         * died between the add and now, its drain has already run and would never see this item,
         * hanging {@code compile.get} forever.
         */
        private void enqueue(Work w) {
            w.enqueuedNanos = System.nanoTime();
            queue.add(w);
            if (!grow()) drainFailQueued(new IOException("zinc worker exited"));
        }

        /**
         * Start a lane when the backlog outruns the lanes that could absorb it. Returns whether the
         * pool has a lane at all — false only when the job is closing, which is the caller's cue
         * that nothing will drain the queue.
         */
        private synchronized boolean grow() {
            if (closed) return !lanes.isEmpty();
            lanes.removeIf(lane -> !lane.alive());
            int free = 0;
            for (Session lane : lanes) {
                if (!lane.working()) free++;
            }
            if (free < queue.size() && lanes.size() < budget) {
                lanes.add(new Session(this, id, lanes.size(), template));
            }
            return !lanes.isEmpty();
        }

        /** Live lanes, dead ones pruned. */
        synchronized int liveLanes() {
            lanes.removeIf(lane -> !lane.alive());
            return lanes.size();
        }

        /**
         * A lane's worker exited. Drop it, and if it was the last one there is no longer anything
         * that will take the queue — fail what is on it rather than let those callers block.
         */
        void laneDied(Session lane, Throwable cause) {
            boolean last;
            synchronized (this) {
                lanes.remove(lane);
                last = lanes.isEmpty() && !closed;
            }
            if (last) drainFailQueued(cause);
        }

        /**
         * Fail every queued (not-yet-dispatched) Work. Safe from any thread — it only polls the
         * concurrent queue and completes futures, both idempotent — and never touches a lane's
         * {@code slot} or {@code inflight}.
         */
        private void drainFailQueued(Throwable e) {
            Work w;
            while ((w = queue.poll()) != null) {
                if (w == Work.POISON) continue;
                w.compile.completeExceptionally(e);
                w.forecast.completeExceptionally(e);
            }
        }

        /**
         * One {@code DONE} per lane, then wait for the workers to exit. The join budget is the
         * pool's, not each lane's: a wedged worker must not multiply the teardown wait by the lane
         * count.
         */
        void close() {
            List<Session> live;
            synchronized (this) {
                closed = true;
                live = List.copyOf(lanes);
                lanes.clear();
            }
            for (int i = 0; i < live.size(); i++) queue.add(Work.POISON);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            for (Session lane : live) {
                lane.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            }
        }
    }

    /** One worker JVM, draining its pool's shared queue one item at a time. */
    private static final class Session {
        private final Lanes owner;
        private final Thread io;
        private volatile @Nullable Work inflight;
        private volatile boolean dead;
        // Held only while a COMPILE/PLAN is in flight, so the resident worker does not pin a
        // PluginSlots permit while idle. Touched only by the io thread.
        private PluginSlots.@Nullable Lease slot;
        // Bounded record of the worker's non-protocol lines, surfaced on a crash; reset at each
        // dispatch so it describes the work item that died, not the worker's first breath.
        private final WorkerTranscript transcript = new WorkerTranscript();

        Session(Lanes owner, long id, int lane, ForkedJavac.Request template) {
            this.owner = owner;
            io = Thread.ofVirtual().name("jk-zinc-host-" + id + "-" + lane).start(() -> run(template));
        }

        boolean alive() {
            return !dead && io.isAlive();
        }

        /** Whether a COMPILE/PLAN is on the wire — a lane that is starting up counts as free. */
        boolean working() {
            return inflight != null;
        }

        void join(long millis) {
            try {
                io.join(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void run(ForkedJavac.Request template) {
            try {
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
                new PluginClient(ForkedJavac.PREFIX)
                        .passthrough(transcript::record)
                        .converseNoSlot(command, (json, convo) -> onLine(json, convo));
            } catch (Exception e) {
                failAll(e);
            } finally {
                dead = true;
                failAll(new IOException("zinc worker exited"));
            }
        }

        private void onLine(String json, PluginProcess.Conversation convo) {
            String t = Jsonl.str(json, PluginProtocol.T);
            if (PluginProtocol.READY.equals(t)) {
                Work next;
                try {
                    next = owner.queue.take();
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
                slot = PluginSlots.acquire();
                try {
                    next.waitNanos = System.nanoTime() - next.enqueuedNanos;
                    next.spec = ForkedJavac.writeSpec(next.req);
                    transcript.reset();
                    inflight = next;
                    convo.send((next.plan ? "PLAN " : "COMPILE ") + next.spec.toAbsolutePath());
                } catch (IOException e) {
                    next.compile.completeExceptionally(e);
                    next.forecast.completeExceptionally(e);
                    releaseSlot();
                }
                return;
            }
            Work w = inflight;
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
                complete(w);
                inflight = null;
                releaseSlot();
                return;
            }
            if (PluginProtocol.ERROR.equals(t)) {
                IOException err = new IOException(Jsonl.str(json, "message"));
                w.compile.completeExceptionally(err);
                w.forecast.completeExceptionally(err);
                deleteSpec(w);
                inflight = null;
                releaseSlot();
            }
        }

        /** Return the in-flight worker slot to the pool (idempotent; io thread only). */
        private void releaseSlot() {
            PluginSlots.Lease s = slot;
            slot = null;
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
            Work cur = inflight;
            inflight = null;
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
