// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.BuildJobFingerprint;
import cc.jumpkick.engine.InFlightBuilds;
import cc.jumpkick.engine.JobWorkers;
import cc.jumpkick.engine.http.JsonOut;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.engine.protocol.ProtoJobs;
import cc.jumpkick.engine.protocol.ProtoLifecycle;
import cc.jumpkick.plugin.protocol.Jsonl;
import cc.jumpkick.runtime.progress.ProgressBarMode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.jspecify.annotations.Nullable;

/**
 * One job lifecycle for CLI (and, in , HTTP/MCP). Admit, heartbeat, cancel, deadline,
 * and request-finish live here as one story — do not split the race comments into hooks.
 */
public final class JobEnvelope {

    /** Process-side callbacks the envelope must not own. */
    public interface Host {
        boolean tryStartBuildPlan();

        void abandonBuildPlanSlot();

        void noteBuildPlanFinished();

        boolean draining();

        long nextRequestId();

        long nowMillis();

        void putMode(long id, ProgressBarMode mode);

        void publishRequestStart(long id, String kind, String dir, long buildNumber);

        void registerAccumulator(
                long id,
                String kind,
                String dir,
                String trigger,
                boolean noTimeline,
                boolean rebuild,
                long buildNumber,
                @Nullable String journalId);

        ReentrantReadWriteLock cacheGate();

        void bindEventRequestId(long id);

        void unbindEventRequestId();

        cc.jumpkick.task.IoLedger runIo(long id);

        InFlightBuilds inFlight();

        @Nullable
        BuildAccumulator accumulatorOf(long id);

        void putLastProgress(long id, double percent);

        int activeBuildPlans();

        JsonOut withProgress(JsonOut payload, long id);

        JsonOut withIo(JsonOut payload, long id);

        void publishEvent(String type, JsonOut payload);

        void clearProgress(long id);

        void writeJournal(long id, boolean cancelled, long millis, @Nullable BufferedWriter writer);

        void maybeIdleBoundary();

        void maybeIdleGc();

        void log(String message);

        String version();

        JkHistoryConfig historyConfig();

        BuildJournal journal();

        String coordOf(String dir);
    }

    private final Host host;
    private final ConcurrentHashMap<Long, LiveJob> liveJobs = new ConcurrentHashMap<>();

    public JobEnvelope(Host host) {
        this.host = host;
    }

    public long submit(String requestLine, JobRequest job, JobTransport transport) {
        return switch (transport) {
            case JobTransport.SocketWatch w -> submitSocket(requestLine, job, w.reader(), w.writer(), false, null);
            case JobTransport.FireAndForget _ ->
                submitSocket(
                        requestLine, job, null, null, true, BuildJobFingerprint.ofRequest(job.verb(), requestLine));
        };
    }

    /**
     * HTTP/MCP: same envelope, no socket. {@code fingerprint} is {@link BuildJobFingerprint#ofHttp}.
     * Throws if the engine is draining or the project is already running.
     */
    public long submitAsync(String requestLine, JobRequest job, String fingerprint) {
        return submitSocket(requestLine, job, null, null, true, fingerprint);
    }

    /**
     * Fork {@code job} onto its own thread (so this method can keep reading the connection for a
     * {@link EngineProtocol#BUILD_CANCEL} or EOF meanwhile) and wait for it to finish.
     */
    private long submitSocket(
            String requestLine,
            JobRequest job,
            @Nullable BufferedReader reader,
            @Nullable BufferedWriter writer,
            boolean detached,
            @Nullable String fingerprintOverride) {
        String threadPrefix = job.threadPrefix();
        String kind = job.verb();
        JobBody runner = job.body();
        boolean plan = job.joinsActivePlans();
        boolean workspaceStream = job.workspaceTerminal();
        // Refuse new jobs while draining (a graceful shutdown is finishing in-flight work). The client
        // normally can't even get here — its handshake sees `draining` and fails first — but guard the
        // server too so a raced/last-moment request is rejected instead of prolonging the drain.
        // A plan claims its slot in the same breath, so shutdown can never observe zero
        // plans for a job that is about to start.
        boolean claimedBuildPlanSlot = false;
        if (plan) {
            claimedBuildPlanSlot = host.tryStartBuildPlan();
        }
        if (plan ? !claimedBuildPlanSlot : host.draining()) {
            if (detached) throw new IllegalStateException("engine is shutting down");
            try {
                send(
                        writer,
                        ProtoLifecycle.error(
                                EngineProtocol.ERR_SHUTTING_DOWN,
                                "the engine is shutting down (draining) — retry; the successor engine takes over"));
            } catch (IOException ignored) {
                // Client vanished mid-refusal — nothing to do; the connection is closing anyway.
            }
            return -1;
        }
        Session.CancelToken cancelToken = Session.CancelToken.live();
        CountDownLatch done = new CountDownLatch(1);
        long eventRequestId = host.nextRequestId();
        // The requesting shell's JK_PROGRESS_MODE rides the request — the resident engine's own
        // startup env is not the client's.
        host.putMode(eventRequestId, ProtoJobs.progressModeOf(requestLine));
        // The kind rides explicitly from the dispatch site (never parsed back out of a thread
        // name); the journal dir falls back to a request's specific location field so non-build
        // requests never record the literal string "null".
        String eventKind = kind;
        String eventDir = journalDir(requestLine);
        long eventStartMillis = host.nowMillis();
        boolean rebuildRun = Jsonl.bool(requestLine, "rebuild", false) || Jsonl.bool(requestLine, "force", false);
        // How the build was started: default "cli"; optimize/calibrate mark synthetic history.
        String trigger = Jsonl.str(requestLine, "trigger");
        if (trigger == null || trigger.isBlank()) trigger = "cli";
        // exclusive fingerprint + start-time build number for journaled kinds.
        String fingerprint = fingerprintOverride != null
                ? fingerprintOverride
                : BuildJobFingerprint.ofRequest(eventKind, requestLine);
        AdmitResult admit = JobAdmit.admit(host, eventRequestId, eventKind, eventDir, fingerprint, trigger);
        if (admit.rejected() != null) {
            InFlightBuilds.Hold h = admit.rejected();
            String label = "test".equals(eventKind) ? "Test" : "Build";
            String msg = label + " #" + h.buildNumber() + " is already running";
            if (detached) {
                if (claimedBuildPlanSlot) host.abandonBuildPlanSlot();
                throw new IllegalStateException(msg);
            }
            try {
                send(writer, ProtoLifecycle.alreadyRunning(h.buildNumber(), h.requestId(), msg));
            } catch (IOException ignored) {
                // client gone
            }
            if (claimedBuildPlanSlot) host.abandonBuildPlanSlot(); // nothing ran — give the slot back
            return -1;
        }
        host.publishRequestStart(eventRequestId, eventKind, eventDir, admit.buildNumber());
        host.registerAccumulator(
                eventRequestId,
                eventKind,
                eventDir,
                trigger,
                Jsonl.bool(requestLine, "noTimeline", false),
                rebuildRun,
                admit.buildNumber(),
                admit.journalId());
        // The slot was already claimed above, atomically with the shutdown check.
        Thread heartbeatThread = null;
        AtomicReference<Thread> runnerRef = new AtomicReference<>();
        // Public jid surface — client tracks this for Ctrl-C / jk cancel.
        if (writer != null) {
            try {
                send(writer, JobAdmit.jobStartLine(host, eventRequestId, eventKind, eventDir, admit));
            } catch (IOException ignored) {
                // client gone before job body — still run cancel registration below
            }
        }
        // Capture this connection thread so remote cancel can wake it off client-readLine. The
        // wake is Thread.interrupt, which on a thread blocked in an InterruptibleChannel read also
        // CLOSES the channel — so only interrupt while actually parked on the read;
        // an interrupt landing after the loop exits would poison teardown I/O instead.
        AtomicBoolean parkedOnRead = new AtomicBoolean(false);
        Thread connectionThread = Thread.currentThread();
        registerLiveJob(
                eventRequestId,
                cancelToken,
                runnerRef,
                writer,
                detached ? null : connectionThread,
                eventDir,
                eventKind,
                workspaceStream);
        Thread started = Thread.ofVirtual().name(threadPrefix, 0).unstarted(() -> {
            // Nothing between the lock and the try: a throw from the setup calls would
            // leak the read lock — one leak and the cache prune's write-lock tryLock never
            // succeeds again for the engine's life — and would strand the in-flight fingerprint
            // and the done latch. The teardown calls are all remove-style and safe to run even
            // when their open never happened.
            if (plan) host.cacheGate().readLock().lock();
            try {
                host.bindEventRequestId(eventRequestId);
                JobWorkers.open(eventRequestId);
                // Every Session this request builds adopts this ledger, so fetches/cache traffic on
                // the shared pools all land in one place (see IoLedger).
                cc.jumpkick.task.IoLedger.open(host.runIo(eventRequestId));
                runner.run(requestLine, cancelToken, writer);
            } finally {
                cc.jumpkick.task.IoLedger.close();
                JobWorkers.close();
                JobWorkers.clear(eventRequestId);
                host.unbindEventRequestId();
                if (plan) host.cacheGate().readLock().unlock();
                // Free exclusive fingerprint as soon as plan work ends — before the
                // connection thread finishes teardown — so a follow-up same-project build is
                // not rejected as already-running while journal/idle chores run.
                host.inFlight().release(eventRequestId);
                unregisterLiveJob(eventRequestId);
                done.countDown();
                // Unblock the connection thread only if it is parked on client readLine
                // waiting for BUILD_CANCEL / EOF — remote cancel finishes the runner without
                // the client writing anything. A blanket interrupt here landed after
                // the read loop too, leaving the flag set through teardown so the journal
                // completion died on ClosedByInterruptException — a phantom "running" job in
                // jk jobs until engine restart.
                if (!detached && parkedOnRead.get()) connectionThread.interrupt();
            }
        });
        runnerRef.set(started);
        started.start(); // register live job + runnerRef before start
        // Keep-alive + optional wall deadline while the job runs.
        // Client stream idle (JK_STREAM_IDLE_MS) resets on each heartbeat line. On deadline:
        // cancel + worker shutdown (grace→force) + interrupt runner; connection join is bounded.
        // User cancel / EOF: same worker policy with a short cancel grace — never hang.
        long heartbeatMs = jobHeartbeatMs();
        long deadlineMs = jobDeadlineMs();
        long graceMs = jobDeadlineGraceMs();
        long cancelGraceMs = JobWorkers.cancelGraceMs();
        if (heartbeatMs > 0 || deadlineMs > 0) {
            heartbeatThread = Thread.ofVirtual().name("jk-job-watchdog", 0).start(() -> {
                long start = host.nowMillis();
                while (done.getCount() > 0) {
                    long elapsed = host.nowMillis() - start;
                    long wait = heartbeatMs > 0 ? heartbeatMs : 1_000L;
                    if (deadlineMs > 0) {
                        long remaining = deadlineMs - elapsed;
                        if (remaining <= 0) {
                            enforceDeadline(eventRequestId, cancelToken, runnerRef.get(), writer, deadlineMs);
                            return;
                        }
                        wait = Math.min(wait, remaining);
                    }
                    try {
                        if (done.await(wait, TimeUnit.MILLISECONDS)) return;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (done.getCount() == 0) return;
                    if (heartbeatMs > 0) {
                        sendQuiet(writer, ProtoLifecycle.heartbeat(host.nowMillis() - start));
                    }
                }
            });
        }
        final Thread watchdog = heartbeatThread;
        Runnable finish = () -> {
            try {
                try {
                    // Stay responsive after remote cancel: the client never writes on this socket, so a
                    // pure blocking readLine would park forever even after the runner finished. Cancel
                    // (and runner teardown) interrupt this thread so we can join and run the finally
                    // safety-net terminal.
                    while (reader != null && done.getCount() > 0) {
                        try {
                            parkedOnRead.set(true);
                            String line = reader.readLine();
                            parkedOnRead.set(false);
                            if (line == null) {
                                // EOF / client gone mid-job — same bounded cancel path (not explicit:
                                // an EOF after a reported failure is the terminal-read race).
                                beginUserCancel(eventRequestId, cancelToken, runnerRef, cancelGraceMs, false);
                                break;
                            }
                            if (EngineProtocol.BUILD_CANCEL.equals(EngineProtocol.typeOf(line))) {
                                // Explicit cancel on this socket: cooperative flag + worker grace→force.
                                beginUserCancel(eventRequestId, cancelToken, runnerRef, cancelGraceMs, true);
                            }
                        } catch (IOException e) {
                            parkedOnRead.set(false);
                            // Interrupt during read (ClosedByInterruptException, etc.) or a real error.
                            if (done.getCount() == 0 || Thread.currentThread().isInterrupted()) {
                                break; // runner done / cancel wake — join below
                            }
                            beginUserCancel(eventRequestId, cancelToken, runnerRef, cancelGraceMs, false);
                            break;
                        }
                    }
                    parkedOnRead.set(false);
                } catch (RuntimeException ignored) {
                    if (done.getCount() > 0) {
                        beginUserCancel(eventRequestId, cancelToken, runnerRef, cancelGraceMs, false);
                    }
                }
                // Clear interrupt so await/join below is not spuriously skipped.
                Thread.interrupted();
                try {
                    // Bound the join so a wedged runner cannot hang the connection forever.
                    if (deadlineMs > 0) {
                        long elapsed = host.nowMillis() - eventStartMillis;
                        long budget = Math.max(1L, deadlineMs + graceMs - elapsed);
                        if (!done.await(budget, TimeUnit.MILLISECONDS)) {
                            enforceDeadline(eventRequestId, cancelToken, runnerRef.get(), writer, deadlineMs);
                            // Last chance for the runner to unwind after worker kill / interrupt.
                            // Cap hard so UX never waits the full 30s grace when the job is deadlocked.
                            long lastChance = Math.min(graceMs, Math.max(cancelGraceMs + 200L, 1_000L));
                            if (!done.await(lastChance, TimeUnit.MILLISECONDS)) {
                                host.log("jk engine: job "
                                        + eventRequestId
                                        + " still running after deadline+"
                                        + lastChance
                                        + "ms grace — abandoned; workers killed");
                            }
                        }
                    } else if (cancelToken.cancelled() && done.getCount() > 0) {
                        // User cancel without wall deadline: join only for cancelGrace + small buffer.
                        long joinBudget = cancelGraceMs + 500L;
                        if (!done.await(joinBudget, TimeUnit.MILLISECONDS)) {
                            JobWorkers.shutdownForRequest(eventRequestId, 0L);
                            interruptRunner(runnerRef.get());
                            if (!done.await(200L, TimeUnit.MILLISECONDS)) {
                                host.log("jk engine: job "
                                        + eventRequestId
                                        + " still running after cancel+"
                                        + joinBudget
                                        + "ms — abandoned; workers force-killed");
                            }
                        }
                    } else {
                        done.await();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                // A late runner/cancel interrupt may have landed after the joins: clear it before any
                // teardown I/O, or journal completion dies on ClosedByInterruptException and jk jobs
                // shows this build as running forever. This thread ends after teardown, so
                // there is nothing to restore the flag for.
                Thread.interrupted();
                if (watchdog != null) watchdog.interrupt();
                // Belts: any leftover workers die now (grace 0 — request is ending).
                JobWorkers.shutdownForRequest(eventRequestId, 0L);
                JobWorkers.clear(eventRequestId);
                // Idempotent: runner finally usually released already; covers admit-without-run paths.
                host.inFlight().release(eventRequestId);
                long elapsedMillis = host.nowMillis() - eventStartMillis;
                // cancelToken.cancelled also trips on the benign end-of-request EOF, so a finished
                // build (success or failure) can look cancelled. Correct it once here for both the
                // dashboard event and the journal.
                boolean cancelled = effectiveCancelled(eventRequestId, cancelToken.cancelled());
                // Same default as BuildAccumulator.toRecord — always emit success.
                BuildAccumulator finishAcc = host.accumulatorOf(eventRequestId);
                boolean success = finishAcc != null ? finishAcc.effectiveSuccess(cancelled) : !cancelled;
                // Pin 100% only on success — a failed build keeps its last true percent, matching the
                // workspace-runner path and the stated policy.
                if (success && !cancelled) host.putLastProgress(eventRequestId, 100.0);
                // Safety net: if the runner was abandoned/interrupted without a terminal
                // wire event, still tell the CLI the job was cancelled so it does not report a crash.
                // Harmless if the runner already sent workspace-/plan-finish (client has returned).
                if (cancelled && writer != null) {
                    // Same shape rule as pushCancelledTerminal: single builds journal as "build" but
                    // their client loop only ends on plan-finish.
                    sendQuiet(writer, cancelledTerminalLine(workspaceStream, eventDir));
                }
                // Release the plan slot before request-finish so status SSE carries the post-finish
                // activeBuildPlans count — Live activity finishes in the same frame.
                if (plan) host.noteBuildPlanFinished();
                host.publishEvent(
                        "request-finish",
                        host.withProgress(
                                host.withIo(
                                        JsonOut.object()
                                                .put("schema", 1)
                                                .put("type", "request-finish")
                                                .put("requestId", eventRequestId)
                                                .put("jid", eventRequestId)
                                                .put("kind", eventKind)
                                                .put("dir", eventDir)
                                                .put("projectId", cc.jumpkick.runtime.ProjectIds.idOf(eventDir))
                                                .put("success", success)
                                                .put("cancelled", cancelled)
                                                .put("millis", elapsedMillis)
                                                .put("activeBuildPlans", host.activeBuildPlans()),
                                        eventRequestId),
                                eventRequestId));
                // Journal first: clearProgress retires the JobSession (drops the accumulator).
                // Writing after retire leaves a permanent running=true stub in jk jobs.
                host.writeJournal(eventRequestId, cancelled, elapsedMillis, writer);
                host.clearProgress(eventRequestId);
                // Idle boundary after finish side-effects so prune/GC see journal + event garbage too.
                // Cache maintenance (plan=false) only GCs when nothing else is in flight.
                if (plan) host.maybeIdleBoundary();
                else host.maybeIdleGc();
            }
        };
        if (detached) {
            Thread.ofVirtual().name("jk-job-join-", 0).start(finish);
            return eventRequestId;
        }
        finish.run();
        return eventRequestId;
    }

    /**
     * User / EOF cancel: set cooperative flag and shut down workers with a short
     * grace→force window on a helper thread so the connection reader is not blocked. Idempotent.
     *
     * <p>Also stamps the accumulator as user-cancelled <em>immediately</em>. Without that, a force-
     * killed runner that never emits userCancelled was journaled as a plain
     * success/failure with the truncated wall-clock — and truncated successes poisoned ETA history.
     */
    public void beginUserCancel(
            long eventRequestId,
            Session.CancelToken cancelToken,
            @Nullable AtomicReference<Thread> runnerRef,
            long cancelGraceMs,
            boolean explicit) {
        cancelToken.cancel();
        markUserCancelled(eventRequestId, explicit);
        Thread.ofVirtual().name("jk-cancel-" + eventRequestId, 0).start(() -> {
            int killed = JobWorkers.shutdownForRequest(eventRequestId, cancelGraceMs);
            interruptRunner(runnerRef != null ? runnerRef.get() : null);
            if (killed > 0) {
                host.log("jk engine: cancel job "
                        + eventRequestId
                        + " — shut down "
                        + killed
                        + " worker process(es) (grace "
                        + cancelGraceMs
                        + "ms)");
            }
        });
    }

    public void registerLiveJob(
            long jid,
            Session.CancelToken token,
            AtomicReference<Thread> runnerRef,
            @Nullable BufferedWriter writer,
            @Nullable Thread connectionThread,
            String dir,
            String kind,
            boolean workspaceStream) {
        liveJobs.put(jid, new LiveJob(token, runnerRef, writer, connectionThread, dir, kind, workspaceStream));
    }

    public void unregisterLiveJob(long jid) {
        liveJobs.remove(jid);
    }

    /**
     * Cancel one live job by jid. Returns {@code false} if unknown/already finished (idempotent soft
     * miss). Covers CLI-socket jobs and HTTP/MCP jobs.
     *
     * <p>Pushes a cancelled terminal on the job's stream immediately so a remote {@code jk cancel}
     * settles the building CLI without waiting for the runner to unwind.
     */
    public boolean cancelJob(long jid) {
        LiveJob job = liveJobs.get(jid);
        if (job == null) return false;
        // Remote `jk cancel` / POST /api/cancel — an explicit signal.
        beginUserCancel(jid, job.token(), job.runnerRef(), JobWorkers.cancelGraceMs(), true);
        // Terminal + reader wake happen off-thread: the job's stream writer can be wedged in a
        // socket write (client not draining), and `jk cancel` / POST /api/cancel must ack
        // without waiting behind that monitor. Order inside the task still matters:
        // terminal first, then the interrupt that may close the channel.
        Thread.ofVirtual().name("jk-cancel-settle-" + jid).start(() -> {
            pushCancelledTerminal(job);
            if (job.connectionThread() != null) {
                try {
                    job.connectionThread().interrupt();
                } catch (RuntimeException ignored) {
                    // best-effort wake
                }
            }
        });
        return true;
    }

    private void pushCancelledTerminal(LiveJob job) {
        if (job.writer() == null) return;
        sendQuiet(job.writer(), cancelledTerminalLine(job.workspaceStream(), job.dir()));
    }

    /**
     * The cancelled terminal matching the stream's real shape: a single-project build registers
     * kind "build" too, but its client loop only ends on {@code plan-finish} — a
     * {@code workspace-finish} there is a no-op and the CLI settles as "engine disconnected"
     * instead of cancelled.
     */
    public static String cancelledTerminalLine(boolean workspaceStream, @Nullable String dir) {
        return workspaceStream
                ? ProtoEvents.workspaceFinish(false, 1, List.of(), true)
                : ProtoEvents.planFinish(dir == null ? "" : dir, false, true);
    }

    /** Cancel every live job whose dir matches (canonical absolute path). */
    public int cancelJobsForDir(String dir) {
        if (dir == null || dir.isBlank()) return 0;
        String want = BuildJobFingerprint.canonicalDir(dir);
        if (want == null || want.isBlank())
            want = Path.of(dir).toAbsolutePath().normalize().toString();
        int n = 0;
        for (var e : liveJobs.entrySet()) {
            String d = e.getValue().dir();
            String got = d == null ? "" : BuildJobFingerprint.canonicalDir(d);
            if (got == null || got.isBlank()) {
                try {
                    got = Path.of(d).toAbsolutePath().normalize().toString();
                } catch (RuntimeException ignored) {
                    got = d;
                }
            }
            if (want.equals(got) && cancelJob(e.getKey())) n++;
        }
        return n;
    }

    private void markUserCancelled(long requestId, boolean explicit) {
        BuildAccumulator a = host.accumulatorOf(requestId);
        if (a != null) a.markUserCancelled(explicit);
    }

    static void interruptRunner(@Nullable Thread runnerThread) {
        if (runnerThread == null) return;
        try {
            runnerThread.interrupt();
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    /**
     * Wall-deadline kill: cooperative cancel + worker grace→force + interrupt
     * runner. Idempotent; safe from the watchdog and the connection thread. Stamps the accumulator so
     * deadline-truncated wall-clock never trains ETA (same as user cancel).
     */
    public void enforceDeadline(
            long eventRequestId,
            Session.CancelToken cancelToken,
            @Nullable Thread runnerThread,
            @Nullable BufferedWriter writer,
            long deadlineMs) {
        cancelToken.cancel();
        markUserCancelled(eventRequestId, true);
        int killed = JobWorkers.shutdownForRequest(eventRequestId, JobWorkers.cancelGraceMs());
        interruptRunner(runnerThread);
        sendQuiet(
                writer,
                ProtoLifecycle.error(
                        EngineProtocol.ERR_DEADLINE,
                        "job exceeded "
                                + deadlineMs
                                + "ms (JK_ENGINE_JOB_DEADLINE_MS); cancelled"
                                + (killed > 0 ? " (killed " + killed + " worker process(es))" : "")));
    }

    /**
     * Whether the build was genuinely cancelled.
     *
     * <p>{@code cancelToken.cancelled} alone is unreliable — it also trips on the benign
     * end-of-request EOF (client closes the socket the instant it reads the terminal message). For a
     * request with an accumulator we trust an explicit stamp from BUILD_CANCEL / mid-job EOF /
     * deadline. A runner that already stamped a terminal outcome is never re-labelled cancelled by
     * that race.
     */
    public boolean effectiveCancelled(long requestId, boolean rawCancelled) {
        BuildAccumulator a = host.accumulatorOf(requestId);
        if (a == null) return rawCancelled;
        if (a.wasCancelled()) return true;
        return rawCancelled && !a.hasOutcome();
    }

    public static String journalDir(String requestLine) {
        String dir = Jsonl.str(requestLine, "dir");
        if (dir != null) return dir;
        String cache = Jsonl.str(requestLine, "cache");
        if (cache != null) return cache;
        return "";
    }

    /**
     * Heartbeat interval while an async job runs. Default 30s; {@code 0} disables.
     * Env: {@code JK_ENGINE_HEARTBEAT_MS}.
     */
    public static long jobHeartbeatMs() {
        return envLongMs("JK_ENGINE_HEARTBEAT_MS", 30_000L);
    }

    /**
     * Optional per-request wall deadline. Default {@code 0} = off. Env: {@code
     * JK_ENGINE_JOB_DEADLINE_MS}.
     */
    public static long jobDeadlineMs() {
        return envLongMs("JK_ENGINE_JOB_DEADLINE_MS", 0L);
    }

    /**
     * Grace after the wall deadline for the runner to unwind after worker kill. Default 30s.
     * Env: {@code JK_ENGINE_JOB_DEADLINE_GRACE_MS}.
     */
    public static long jobDeadlineGraceMs() {
        return envLongMs("JK_ENGINE_JOB_DEADLINE_GRACE_MS", 30_000L);
    }

    private static long envLongMs(String name, long defaultMs) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return defaultMs;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultMs;
        }
    }

    static void send(BufferedWriter writer, String line) throws IOException {
        writer.write(line);
        writer.write('\n');
        writer.flush();
    }

    static void sendQuiet(@Nullable BufferedWriter writer, String line) {
        if (writer == null) return;
        try {
            send(writer, line);
        } catch (IOException ignored) {
            // the cancel-watching read loop will notice the same disconnect
        }
    }
}
