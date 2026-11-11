// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.config.RequestShell;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.engine.plugin.PluginProcess;
import cc.jumpkick.engine.plugin.WorkerLeases;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

/**
 * One resident compiler worker JVM and the compiles in flight on it.
 *
 * <p>Three engine threads serve a worker. The {@code io} thread forks the process and waits for it
 * to end, then reports the death to the {@link CompilerPool}. The frame reader, a platform thread of
 * {@link PluginProcess#converseOnChannel}, hands every line the worker answers to {@link #onFrame},
 * which never blocks: it routes the frame by id to its item and completes the item when its result
 * comes. The dispatcher takes the next item off the pool's queue whenever the pool says this worker
 * may run another ({@link CompilerPool#mayAdmit}), writes its spec and sends it. So a worker busy
 * compiling is always heard, and a slow spec write never holds up a frame.
 *
 * <p>Which items are in flight is {@link #inflight}, keyed by id. Every way an item leaves it — its
 * result, its error, its cancel, the worker's death — removes it under the pool's lock, and only the
 * one that removed it answers it.
 */
final class CompilerWorker {

    /** What a worker is leased before it reports its heap: about what an idle one holds. */
    static final long FIRST_LEASE_BYTES = 512L << 20;

    /** {@value #CONCURRENCY_ENV}: compiles one worker runs at once, for measurement; unset is one per core. */
    static final String CONCURRENCY_ENV = "JK_COMPILE_CONCURRENCY";

    private static final Clock CLOCK = Clock.SYSTEM;

    /** How a worker comes to life. Production forks the JVM; tests answer frames from a thread. */
    @FunctionalInterface
    interface Launcher {
        /** Run the worker until it exits; throw what ended it when that was not a clean exit. */
        void run(CompilerWorker worker) throws Exception;
    }

    /** One {@link PluginProtocol#HEAP} reading. */
    record HeapReading(long usedAfterGc, long committed, long max, double gcFrac, long metaspace, int inflight) {

        static HeapReading parse(String json) {
            return new HeapReading(
                    Jsonl.longValue(json, PluginProtocol.USED_AFTER_GC, 0),
                    Jsonl.longValue(json, "committed", 0),
                    Jsonl.longValue(json, "max", 0),
                    Jsonl.doubleValue(json, "gc_frac", 0.0),
                    Jsonl.longValue(json, "metaspace", 0),
                    Jsonl.intValue(json, "inflight", 0));
        }
    }

    private final CompilerPool pool;

    /** The {@link JobWorkers} scope the process runs under: its own, or a job's for a job-scoped pool. */
    final long scope;

    final Map<Long, CompileWork> inflight = new ConcurrentHashMap<>();

    /** Bounded record of what the worker printed; attached to every item its death fails. */
    final WorkerTranscript transcript = new WorkerTranscript();

    private final Launcher launcher;
    private volatile PluginProcess.@Nullable Conversation channel;
    private volatile int threads;
    private volatile long pid = -1;
    private final AtomicInteger peakInflight = new AtomicInteger();
    private volatile boolean dead;
    private volatile boolean retiring;
    private volatile @Nullable HeapReading heap;
    private volatile long trimmedTo = -1;
    private volatile WorkerLeases.@Nullable Grant lease;
    private volatile @Nullable Process process;
    private volatile @Nullable Runnable killer;
    private volatile boolean outOfHeap;
    private volatile @Nullable Long stoppedForJob;

    /** Bumped each time the worker goes idle, so a trim scheduled for an earlier idle is dropped. */
    private final AtomicInteger idleGeneration = new AtomicInteger();

    private @Nullable Thread io;

    CompilerWorker(CompilerPool pool, long scope, Launcher launcher) {
        this.pool = pool;
        this.scope = scope;
        this.launcher = launcher;
    }

    /**
     * Start the worker and its dispatcher, on threads that inherit nothing from the request that
     * happened to need the worker first — no session, no job scope, no step: the worker belongs to
     * its pool, binds its own scope before it forks, and each item brings its own session.
     */
    void start() {
        io = detached("jk-javac-worker-" + scope, this::drive);
        detached("jk-javac-dispatch-" + scope, this::dispatchLoop);
    }

    /** A virtual thread with no inherited context. Reads no session of a request. */
    static Thread detached(String name, Runnable body) {
        return Thread.ofVirtual()
                .name(name)
                .inheritInheritableThreadLocals(false)
                .start(body);
    }

    /** Whether the worker has said hello and not died: it can take items. */
    boolean ready() {
        return threads > 0 && !dead;
    }

    boolean dead() {
        return dead;
    }

    boolean retiring() {
        return retiring;
    }

    /** Compiles the worker runs at once, from its hello; 0 before. */
    int threads() {
        return threads;
    }

    /** An item just went into flight: remember the most this worker has run at once. */
    void noteInflight() {
        peakInflight.accumulateAndGet(inflight.size(), Math::max);
    }

    /** The most items this worker has had in flight at once. */
    int peakInflight() {
        return peakInflight.get();
    }

    /** The worker process's pid, from its hello; -1 before. */
    long pid() {
        return pid;
    }

    /** The latest heap reading, or null before the first. */
    @Nullable
    HeapReading heap() {
        return heap;
    }

    /** What the heap held after the last trim, or -1 before one. */
    long trimmedTo() {
        return trimmedTo;
    }

    /** The process, once forked: how {@link #kill} stops it. */
    void process(Process forked) {
        process = forked;
    }

    /** Test seam: how {@link #kill} stops a worker that has no process. */
    void killWith(Runnable stop) {
        killer = stop;
    }

    /** The worker ran out of heap: its death lowers its pool's admission for a while. */
    void ranOutOfHeap() {
        outOfHeap = true;
    }

    boolean outOfHeap() {
        return outOfHeap;
    }

    /**
     * The job this worker was stopped for, when it was stopped because that job's compile would
     * not answer its cancel: its death is no fault of the other jobs' compiles on it.
     */
    @Nullable
    Long stoppedForJob() {
        return stoppedForJob;
    }

    void join(long millis) {
        Thread t = io;
        if (t == null) return;
        try {
            t.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void drive() {
        Long previous = JobWorkers.bind(scope);
        Throwable ended = null;
        try {
            launcher.run(this);
        } catch (Exception e) {
            ended = e;
        } finally {
            try {
                dead = true;
                pool.workerDied(this, ended != null ? ended : new IOException("zinc worker exited"));
            } finally {
                JobWorkers.restore(previous);
            }
        }
    }

    /**
     * The worker's command channel is open: say hello once the worker connects. Sent from a thread
     * of its own, because the send waits for the connection and the fork has not happened yet.
     */
    void attach(PluginProcess.Conversation conversation) {
        channel = conversation;
        String hello = hello(concurrencyOverride());
        detached("jk-javac-hello-" + scope, () -> sendWhenConnected(conversation, hello, () -> dead));
    }

    /**
     * Send {@code line} once the worker connects, however long that takes while it lives: a worker
     * that connects after one connect wait still gets its hello, where giving up would leave it
     * waiting for one forever and holding its pool slot. Stops when {@code gone} or the channel closes.
     */
    static void sendWhenConnected(PluginProcess.Conversation conversation, String line, BooleanSupplier gone) {
        while (!gone.getAsBoolean()) {
            try {
                conversation.send(line);
                return;
            } catch (UncheckedIOException e) {
                // Only a connect wait that ran out is worth another; a closed channel is the end.
                if (!(e.getCause() instanceof SocketTimeoutException)) return;
            } catch (RuntimeException e) {
                return;
            }
        }
    }

    /** The {@code hello} command: a thread count only when one was asked for. */
    static String hello(int threads) {
        JsonFields hello = JsonFields.object().string(PluginProtocol.OP, PluginProtocol.HELLO);
        if (threads > 0) hello.number("threads", threads);
        return hello.finish();
    }

    /** {@value #CONCURRENCY_ENV}, or 0 when unset or not a positive integer. */
    static int concurrencyOverride() {
        String v = ProcessEnv.get(CONCURRENCY_ENV);
        if (v == null || v.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The lease the process was started under, moved to what the worker reports from now on. */
    void leased(WorkerLeases.Grant grant) {
        lease = grant;
    }

    /** A line the worker printed rather than answered: kept for the report should it die. */
    void output(String line) {
        transcript.record(line);
    }

    /**
     * Send {@code json} to the worker if it is connected now. A worker that never connected, or is
     * gone, drops the frame: nothing that tears a worker down may wait on its connection.
     */
    void send(String json) {
        PluginProcess.Conversation c = channel;
        try {
            if (c == null || !c.offer(json)) Log.debug("zinc worker " + scope + " not connected; dropped " + json);
        } catch (RuntimeException e) {
            Log.debug("zinc worker " + scope + " send failed: " + e.getMessage());
        }
    }

    /** Ask the worker to stop the compile {@code id}; it answers {@code cancelled} unless it finished first. */
    void cancel(long id) {
        send(JsonFields.object()
                .string(PluginProtocol.OP, PluginProtocol.OP_CANCEL)
                .number(PluginProtocol.ID, id)
                .finish());
    }

    /**
     * The worker has nothing in flight: tell it to collect once it has stayed idle for the pool's
     * {@link CompilerPool#trimAfterIdle}. A compile in between cancels the trim, so a build that pauses between
     * modules does not pay a full collection at every pause.
     */
    void idleNow() {
        int generation = idleGeneration.incrementAndGet();
        detached("jk-javac-trim-" + scope, () -> {
            try {
                Thread.sleep(pool.trimAfterIdle);
            } catch (InterruptedException e) {
                return;
            }
            if (idleGeneration.get() == generation && inflight.isEmpty() && !dead && pool.idle()) trim();
        });
    }

    /** A compile went into flight: a pending trim no longer applies. */
    void busyNow() {
        idleGeneration.incrementAndGet();
    }

    /** Ask the worker to collect now that it has nothing to do. */
    void trim() {
        transcript.reset();
        send(JsonFields.object()
                .string(PluginProtocol.OP, PluginProtocol.OP_TRIM)
                .finish());
    }

    /** Finish what is in flight and exit. */
    void retire() {
        retiring = true;
        send(JsonFields.object()
                .string(PluginProtocol.OP, PluginProtocol.OP_DONE)
                .finish());
    }

    /** Stop the process now; its in-flight items go the way of any death. */
    void kill() {
        retiring = true;
        Runnable stop = killer;
        Process p = process;
        if (stop != null) stop.run();
        else if (p != null) JobWorkers.destroyTree(p);
        else JobWorkers.shutdownForRequest(scope, 0L);
    }

    /** Stop the process because {@code job}'s compile on it would not answer its cancel. */
    void killFor(long job) {
        stoppedForJob = job;
        kill();
    }

    /** One line the worker answered. Runs on the frame reader and never blocks on the pool's queue. */
    void onFrame(String json) {
        String t = Jsonl.str(json, PluginProtocol.T);
        if (t == null) return;
        switch (t) {
            case PluginProtocol.HELLO -> {
                pid = Jsonl.longValue(json, "pid", -1);
                threads = Math.max(1, Jsonl.intValue(json, "threads", 1));
                pool.wake();
            }
            case PluginProtocol.HEAP -> onHeap(HeapReading.parse(json));
            case PluginProtocol.TRIMMED -> trimmedTo = Jsonl.longValue(json, PluginProtocol.USED_AFTER_GC, -1);
            case PluginProtocol.DIAGNOSTIC -> onDiagnostic(json);
            case PluginProtocol.PROVENANCE -> onProvenance(json);
            case PluginProtocol.RESULT -> onResult(json);
            case PluginProtocol.ERROR -> onError(json);
            case PluginProtocol.CANCELLED -> {
                CompileWork w = pool.settle(this, idOf(json));
                if (w != null) {
                    deleteSpec(w);
                    w.fail(new InterruptedIOException("compile cancelled"));
                }
            }
            default -> {
                // A frame this engine does not know: nothing waits on it.
            }
        }
    }

    private static long idOf(String json) {
        return Jsonl.longValue(json, PluginProtocol.ID, -1);
    }

    /** The lease follows what the worker has committed, with the JVM's own overhead on top. */
    private void onHeap(HeapReading reading) {
        heap = reading;
        WorkerLeases.Grant g = lease;
        if (g != null && reading.committed() > 0) g.resize(WorkerLeases.jvmLease(reading.committed()));
    }

    private void onDiagnostic(String json) {
        CompileWork w = inflight.get(idOf(json));
        if (w == null) return;
        // Through WorkerDiagnostics, never a bare `new Diagnostic(...)`: downstream consumers scrape
        // the MESSAGE for the locus, so the worker's file/line is folded into a javac-style header.
        w.diagnostics.add(WorkerDiagnostics.located(
                Jsonl.str(json, "sev"),
                Jsonl.str(json, "file"),
                Jsonl.longValue(json, "line", 0),
                Jsonl.longValue(json, "col", 0),
                Jsonl.str(json, "msg"),
                Jsonl.str(json, "key")));
    }

    private void onProvenance(String json) {
        CompileWork w = inflight.get(idOf(json));
        String gen = Jsonl.str(json, "gen");
        if (w == null || gen == null) return;
        Set<Path> origins = new TreeSet<>();
        for (String s : Jsonl.strArray(json, "src")) origins.add(Path.of(s));
        w.generated.put(Path.of(gen), origins);
    }

    private void onResult(String json) {
        CompileWork w = inflight.get(idOf(json));
        if (w == null) return;
        w.status = Jsonl.str(json, "status");
        w.outcome = Jsonl.str(json, "outcome");
        w.reason = Jsonl.str(json, "reason");
        for (String s : Jsonl.strArray(json, "compiled")) w.compiledSources.add(Path.of(s));
        w.whys.addAll(Jsonl.strArray(json, "whys"));
        // Free the credit before waking the submitter: its next module may be submitted at once.
        if (pool.settle(this, w.id) == w) complete(w);
    }

    private void onError(String json) {
        long id = idOf(json);
        String message = String.valueOf(Jsonl.str(json, "message"));
        if (id < 0) {
            output("zinc worker: " + message);
            return;
        }
        CompileWork w = pool.settle(this, id);
        if (w == null) return;
        deleteSpec(w);
        w.fail(new IOException(message));
    }

    /** Take items while the pool lets this worker run more, until the worker dies or retires. */
    private void dispatchLoop() {
        try {
            while (true) {
                CompileWork next = pool.nextFor(this);
                if (next == null) return;
                dispatch(next);
            }
        } catch (InterruptedException e) {
            // The engine is stopping.
        } catch (RuntimeException | Error e) {
            // A dispatcher that dies leaves its worker unable to take work: stop the worker, so what
            // it holds runs again on a fresh one instead of waiting forever.
            Log.warn("jk engine: the compiler worker's dispatcher failed: " + e, e);
            kill();
        }
    }

    /**
     * Write {@code next}'s spec and send it. The item is already in flight, so a death meanwhile puts
     * it back; a spec that cannot be written fails this item alone.
     */
    private void dispatch(CompileWork next) {
        next.waitNanos = CLOCK.nanos() - next.enqueuedNanos;
        Path spec;
        try {
            ForkedJavac.Request req = next.req;
            Map<String, String> shell = next.shell;
            spec = SessionContext.where(next.session, () -> RequestShell.where(shell, () -> pool.specs.write(req)));
        } catch (Exception e) {
            // Whatever the write threw (a classpath jar not on disk is an IllegalStateException)
            // fails this item; the worker takes the next.
            if (pool.settle(this, next.id) == next) next.fail(e);
            return;
        }
        if (!pool.attachSpec(this, next, spec)) {
            deleteQuietly(spec);
            return;
        }
        send(JsonFields.object()
                .string(PluginProtocol.OP, next.plan ? PluginProtocol.OP_PLAN : PluginProtocol.OP_COMPILE)
                .number(PluginProtocol.ID, next.id)
                .string("spec", spec.toAbsolutePath().toString())
                .finish());
    }

    /** The items this dead worker still held, taken out of flight; called by the pool under its lock. */
    List<CompileWork> takeInflight() {
        List<CompileWork> held = new ArrayList<>(inflight.values());
        // In the order they were asked for, so they run again in that order.
        held.sort(Comparator.comparingLong(w -> w.id));
        inflight.clear();
        return held;
    }

    static void deleteSpec(CompileWork w) {
        Path spec = w.spec;
        if (spec != null) deleteQuietly(spec);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
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
}
