// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.engine.JobWorkers;
import cc.jumpkick.engine.plugin.PluginAot;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.plugin.PluginLoader;
import cc.jumpkick.jdk.HostPlatform;
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
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
        final ForkedJavac.Request req;
        final boolean plan;
        final CompletableFuture<ForkedJavac.Result> compile = new CompletableFuture<>();
        final CompletableFuture<ForkedJavac.Plan> forecast = new CompletableFuture<>();
        final List<CompileResult.Diagnostic> diagnostics = new ArrayList<>();
        final Map<Path, Set<Path>> generated = new TreeMap<>();
        final List<Path> compiledSources = new ArrayList<>();
        final List<String> whys = new ArrayList<>();
        Path spec;
        String status;
        String outcome;
        String reason;

        private Work(ForkedJavac.Request req, boolean plan) {
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
        private volatile Work inflight;
        private volatile boolean dead;
        // Held only while a COMPILE/PLAN is in flight, so the resident worker does not pin a
        // PluginSlots permit while idle (JK-2284). Touched only by the io thread.
        private cc.jumpkick.engine.plugin.PluginSlots.Lease slot;
        // Bounded ring of the worker's most recent non-protocol lines, surfaced on a crash (JK-2296).
        private static final int TAIL_MAX = 50;
        private final ConcurrentLinkedDeque<String> passthroughTail = new ConcurrentLinkedDeque<>();

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
         * this item, hanging {@code compile.get()} forever (JK-2285). The re-check fails it here.
         */
        private void enqueue(Work w) {
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
                Path hostJavaHome = cc.jumpkick.jdk.JavaHomes.runningJavaHome();
                boolean win = HostPlatform.isWindows();
                Path javaExe = hostJavaHome.resolve("bin").resolve(win ? "java.exe" : "java");
                String workerCp = ForkedJavac.workerClasspath(template);
                List<String> jvmFlags = new ArrayList<>(PluginAot.javaCompilerFlags(
                        hostJavaHome,
                        workerCp,
                        (aotOutput, scratch) ->
                                ForkedJavac.trainerCommand(template, workerCp, hostJavaHome, aotOutput, scratch)));
                jvmFlags.addAll(cc.jumpkick.engine.plugin.JvmOptions.batchFlags(1));
                List<String> command = PluginLoader.command(javaExe, workerCp, jvmFlags, List.of("--pull"));
                new PluginClient(ForkedJavac.PREFIX)
                        .passthrough(this::recordTail)
                        .converseNoSlot(command, (json, convo) -> onLine(json, convo));
            } catch (Exception e) {
                failAll(e);
            } finally {
                dead = true;
                failAll(new IOException("zinc worker exited"));
            }
        }

        private void onLine(String json, cc.jumpkick.engine.plugin.PluginProcess.Conversation convo) {
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
                // RESULT/ERROR/failure so an idle session holds none (JK-2284).
                slot = cc.jumpkick.engine.plugin.PluginSlots.acquire();
                try {
                    next.spec = ForkedJavac.writeSpec(next.req);
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
                String file = Jsonl.str(json, "file");
                w.diagnostics.add(new CompileResult.Diagnostic(
                        CompileResult.Severity.fromName(Jsonl.str(json, "sev")),
                        file == null ? null : Path.of(file),
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

        /** Keep the last {@link #TAIL_MAX} non-protocol worker lines for crash diagnostics. */
        private void recordTail(String line) {
            passthroughTail.addLast(line);
            while (passthroughTail.size() > TAIL_MAX) passthroughTail.pollFirst();
        }

        /** Return the in-flight worker slot to the pool (idempotent; io thread only). */
        private void releaseSlot() {
            cc.jumpkick.engine.plugin.PluginSlots.Lease s = slot;
            slot = null;
            if (s != null) s.close();
        }

        /** Delete a work item's spec temp file on every terminal path (JK-2296). */
        private static void deleteSpec(Work w) {
            if (w == null || w.spec == null) return;
            try {
                Files.deleteIfExists(w.spec);
            } catch (IOException ignored) {
                // temp spec — best effort
            }
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
                w.compile.complete(new ForkedJavac.Result(true, List.of(), Map.of(), List.of()));
            } else {
                boolean success = "OK".equals(w.status);
                w.compile.complete(new ForkedJavac.Result(success, w.diagnostics, w.generated, w.compiledSources));
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
         * with no cause (JK-2296).
         */
        private Throwable withWorkerTail(Throwable e) {
            if (passthroughTail.isEmpty()) return e;
            String tail = String.join("\n", passthroughTail);
            return new IOException(e.getMessage() + "\n--- zinc worker output ---\n" + tail, e);
        }

        /**
         * Fail every queued (not-yet-dispatched) Work. Safe to call from any thread — it only polls
         * the concurrent queue and completes futures (both idempotent), and never touches the io
         * thread's {@code slot}/{@code inflight}. Used both by {@link #failAll} and by {@link
         * #enqueue}'s post-add dead re-check (JK-2285).
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
