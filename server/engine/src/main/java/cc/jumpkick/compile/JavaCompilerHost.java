// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

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
 * One Zinc {@code jk-java-compiler} JVM per job ({@link JobWorkers} request scope). Compile and
 * {@code jk explain} PLAN share it; {@link #end(long)} / job teardown send {@code DONE} and the
 * process exits. No engine residency.
 */
public final class JavaCompilerHost {

    private static final ConcurrentHashMap<Long, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final AtomicLong EPHEMERAL = new AtomicLong(-1L);

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

    /** Send {@code DONE} and drop the session for {@code requestId}. Process kill is {@link JobWorkers}. */
    public static void end(long requestId) {
        Session s = SESSIONS.remove(requestId);
        if (s != null) s.close();
    }

    static ForkedJavac.Result compile(ForkedJavac.Request req) {
        Long id = JobWorkers.currentRequestId();
        if (id == null) return ForkedJavac.oneshot(req);
        return session(id, req).submit(Work.compile(req));
    }

    static ForkedJavac.Plan plan(ForkedJavac.Request req) {
        Long id = JobWorkers.currentRequestId();
        if (id == null) return ForkedJavac.oneshotPlan(req);
        return session(id, req).submitPlan(Work.plan(req));
    }

    private static Session session(long id, ForkedJavac.Request req) {
        return SESSIONS.compute(id, (k, existing) -> {
            if (existing != null && existing.alive()) return existing;
            return new Session(id, req);
        });
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

    private static final class Session {
        private final BlockingQueue<Work> queue = new LinkedBlockingQueue<>();
        private final Thread io;
        private volatile @Nullable Work inflight;
        private volatile boolean dead;
        // Held only while a COMPILE/PLAN is in flight, so the resident worker does not pin a
        // PluginSlots permit while idle. Touched only by the io thread.
        private PluginSlots.@Nullable Lease slot;
        // Bounded record of the worker's non-protocol lines, surfaced on a crash; reset at each
        // dispatch so it describes the work item that died, not the worker's first breath.
        private final WorkerTranscript transcript = new WorkerTranscript();

        Session(long id, ForkedJavac.Request template) {
            io = Thread.ofVirtual().name("jk-zinc-host-" + id).start(() -> run(template));
        }

        boolean alive() {
            return !dead && io.isAlive();
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
         * Enqueue work, then re-check {@link #dead}: if the worker died between the caller's {@code
         * alive()} check and this add, {@code failAll}'s drain has already run and would never see
         * this item, hanging {@code compile.get} forever. The re-check fails it here.
         */
        private void enqueue(Work w) {
            w.enqueuedNanos = System.nanoTime();
            queue.add(w);
            if (dead) drainFailQueued(new IOException("zinc worker exited"));
        }

        void close() {
            queue.add(Work.POISON);
            try {
                io.join(TimeUnit.SECONDS.toMillis(15));
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
                    next = queue.take();
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

        private void failAll(Throwable e) {
            releaseSlot();
            Work cur = inflight;
            if (cur != null) {
                deleteSpec(cur);
                Throwable withTail = withWorkerTail(e);
                cur.compile.completeExceptionally(withTail);
                cur.forecast.completeExceptionally(withTail);
            }
            drainFailQueued(e);
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

        /**
         * Fail every queued (not-yet-dispatched) Work. Safe to call from any thread — it only polls
         * the concurrent queue and completes futures (both idempotent), and never touches the io
         * thread's {@code slot}/{@code inflight}. Used both by {@link #failAll} and by {@link
         * #enqueue}'s post-add dead re-check.
         */
        private void drainFailQueued(Throwable e) {
            Work w;
            while ((w = queue.poll()) != null) {
                if (w == Work.POISON) continue;
                w.compile.completeExceptionally(e);
                w.forecast.completeExceptionally(e);
            }
        }

        private static RuntimeException unwrap(Exception e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            if (c instanceof RuntimeException re) return re;
            if (c instanceof IOException io) return new UncheckedIOException(io);
            return new RuntimeException(c);
        }
    }
}
