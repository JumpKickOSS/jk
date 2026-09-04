// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.compile.JavaCompilerHost;
import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.config.JobLimits;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.BuildJobFingerprint;
import cc.jumpkick.engine.InFlightBuilds;
import cc.jumpkick.engine.JobWorkers;
import cc.jumpkick.engine.JsonOut;
import cc.jumpkick.engine.WireWriter;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.InputTrees;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.runtime.ProjectIds;
import cc.jumpkick.task.IoLedger;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProtoJobs;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import cc.jumpkick.wire.runtime.progress.ProgressBarMode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.SocketChannel;
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
 * One job lifecycle for CLI, HTTP and MCP submissions. Admit, heartbeat, cancel, deadline, and
 * request-finish live here as one story — do not split the race comments into hooks.
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

        IoLedger runIo(long id);

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

    /**
     * Why a cancelled row was cancelled, for the two signals this envelope can tell apart. Both
     * ride the journal as the {@code cancelled} warning diagnostic and {@code request-finish}'s
     * {@code cancelReason}, next to the wall deadline's own sentence in {@link #enforceDeadline}.
     */
    private static final String CANCEL_BY_USER = "cancelled by the user (Ctrl-C, jk cancel, or the dashboard)";

    private static final String CANCEL_BY_DISCONNECT = "the client disconnected before the job finished";

    private final Host host;
    private final JobLimits limits;
    private final ConcurrentHashMap<Long, LiveJob> liveJobs = new ConcurrentHashMap<>();

    /** {@code limits} come from the engine's resolved config; the envelope never reads the environment. */
    public JobEnvelope(Host host, JobLimits limits) {
        this.host = host;
        this.limits = limits;
    }

    /** Detached admission refusal: a same-fingerprint job is already in flight. */
    public static final class AlreadyRunning extends IllegalStateException {
        private final long jid;
        private final long buildNumber;

        AlreadyRunning(String message, long jid, long buildNumber) {
            super(message);
            this.jid = jid;
            this.buildNumber = buildNumber;
        }

        public long jid() {
            return jid;
        }

        public long buildNumber() {
            return buildNumber;
        }
    }

    /**
     * One submit path for every transport. {@link JobTransport.SocketWatch} forks the job and keeps
     * reading the connection for EOF (cancellation arrives out-of-band as
     * {@link EngineProtocol#CANCEL_REQUEST}), joining before
     * return; {@link JobTransport.FireAndForget} returns the jid immediately (progress is the
     * sink) and throws {@link AlreadyRunning} / {@link IllegalStateException} on refusal.
     */
    public long submit(String requestLine, JobRequest job, JobTransport transport) {
        BufferedReader reader = transport instanceof JobTransport.SocketWatch w ? w.reader() : null;
        BufferedWriter writer = transport instanceof JobTransport.SocketWatch w ? w.writer() : null;
        SocketChannel channel = transport instanceof JobTransport.SocketWatch w ? w.channel() : null;
        boolean detached = transport instanceof JobTransport.FireAndForget;
        String threadPrefix = job.threadPrefix();
        String kind = job.verb();
        JobBody runner = job.body();
        boolean plan = job.joinsActivePlans();
        boolean workspaceStream = job.workspaceTerminal();
        // Refuse new jobs while draining. The listener is already closed, so this is the race
        // on a connection accepted just before yield, or an already-open session.
        // A plan claims its slot in the same breath, so shutdown can never observe zero
        // plans for a job that is about to start.
        boolean claimedBuildPlanSlot = false;
        if (plan) {
            claimedBuildPlanSlot = host.tryStartBuildPlan();
        }
        if (plan ? !claimedBuildPlanSlot : host.draining()) {
            if (detached) throw new IllegalStateException("engine is shutting down");
            try {
                WireWriter.send(
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
        String fingerprint = BuildJobFingerprint.ofRequest(eventKind, requestLine);
        AdmitResult admit = JobAdmit.admit(host, eventRequestId, eventKind, eventDir, fingerprint, trigger);
        if (admit.rejected() != null) {
            InFlightBuilds.Hold h = admit.rejected();
            String label = "test".equals(eventKind) ? "Test" : "Build";
            String msg = label + " #" + h.buildNumber() + " is already running";
            if (detached) {
                if (claimedBuildPlanSlot) host.abandonBuildPlanSlot();
                throw new AlreadyRunning(msg, h.requestId(), h.buildNumber());
            }
            try {
                WireWriter.send(writer, ProtoLifecycle.alreadyRunning(h.buildNumber(), h.requestId(), msg));
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
                !job.kind().writesTimeline() || Jsonl.bool(requestLine, "noTimeline", false),
                rebuildRun,
                admit.buildNumber(),
                admit.journalId());
        // The slot was already claimed above, atomically with the shutdown check.
        Thread heartbeatThread = null;
        AtomicReference<Thread> runnerRef = new AtomicReference<>();
        // Public jid surface — client tracks this for Ctrl-C / jk cancel.
        if (writer != null) {
            try {
                WireWriter.send(writer, JobAdmit.jobStartLine(host, eventRequestId, eventKind, eventDir, admit));
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
                channel,
                detached ? null : connectionThread,
                eventDir,
                eventKind,
                workspaceStream);
        Thread started = Thread.ofVirtual().name(threadPrefix, 0).unstarted(() -> {
            IoLedger io = host.runIo(eventRequestId);
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
                IoLedger.open(io);
                // Run-scoped notices join this request's stream for the run's life; the finally
                // removes the sink, or a later run's notice would ride the wrong request.
                RunNotices.openSink(io, (code, message) -> publishNotice(eventRequestId, eventDir, writer, message));
                JobOutcome outcome;
                try {
                    outcome = runner.run(requestLine, cancelToken, writer);
                } catch (Throwable t) {
                    // An escaped throw must not impersonate Declined: with clean rows already
                    // recorded and no failure row, the derived verdict would read green. Rule
                    // failure, name the exception on the record, and fall into the teardown.
                    outcome = JobOutcome.failed(Exit.SOFTWARE);
                    BuildAccumulator thrown = host.accumulatorOf(eventRequestId);
                    if (thrown != null) thrown.addEscapedThrow(t);
                    reportDeadJob(eventRequestId, eventDir, writer, t);
                }
                // The one success law: the body's verdict is stamped here, nowhere else. A
                // declined verdict leaves the journal to the accumulated facts.
                BuildAccumulator acc = host.accumulatorOf(eventRequestId);
                if (acc != null) acc.stamp(outcome);
            } catch (Throwable t) {
                // The setup and teardown around the body are outside its own catch, and a throw
                // there would otherwise reach nothing but the default uncaught handler: no log
                // line, no wire terminal, and a client that reads EOF and blames a crash.
                reportDeadJob(eventRequestId, eventDir, writer, t);
            } finally {
                RunNotices.closeSink(io);
                InputTrees.finishJob();
                IoLedger.close();
                // Kill leftovers first, THEN drain the Zinc session: if the worker is mid-compile
                // its io thread is blocked in readLine and never sees end()'s POISON, so end() would
                // burn its full 15s join before this force-kill ran. Killing the process
                // first unblocks readLine, so end()'s join returns promptly.
                // Never clear() the registry without shutdown, or a racing cancel thread's
                // shutdownForRequest finds an empty set and plugin/javac children keep running.
                JobWorkers.shutdownForRequest(eventRequestId, 0L);
                JavaCompilerHost.end(eventRequestId);
                JobWorkers.close();
                host.unbindEventRequestId();
                if (plan) host.cacheGate().readLock().unlock();
                // Unregister the live job BEFORE releasing the in-flight hold: waiters watch the
                // hold, cancel watches liveJobs — this order means a job never looks finished
                // while cancelJob would still succeed. The hold still frees before the connection
                // thread's teardown so a follow-up same-project build is not rejected.
                unregisterLiveJob(eventRequestId);
                host.inFlight().release(eventRequestId);
                done.countDown();
                // Unblock the connection thread only if it is parked on client readLine
                // waiting for EOF — remote cancel finishes the runner without
                // the client writing anything. Only while actually parked: a wake that lands
                // after the read loop poisons teardown I/O instead (a stray interrupt once killed
                // journal completion with ClosedByInterruptException, leaving a permanent
                // "running" job in jk jobs).
                if (!detached && parkedOnRead.get()) wakeOffClientRead(channel, connectionThread);
            }
        });
        runnerRef.set(started);
        started.start(); // register live job + runnerRef before start
        // Keep-alive + optional wall deadline while the job runs, per JobLimits.
        // Client stream idle (JK_STREAM_IDLE_MS) resets on each heartbeat line. On deadline:
        // cancel + worker shutdown (grace→force) + interrupt runner; connection join is bounded.
        // User cancel / EOF: same worker policy with a short cancel grace — never hang.
        long heartbeatMs = limits.heartbeatMs();
        long deadlineMs = limits.deadlineMs();
        long graceMs = limits.deadlineGraceMs();
        long cancelGraceMs = JobWorkers.cancelGraceMs();
        // Heartbeats are a wire line — a detached job has no writer, so its watchdog exists only
        // to enforce a wall deadline. No deadline, no writer → no thread and no idle wakeups.
        if ((heartbeatMs > 0 && writer != null) || deadlineMs > 0) {
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
                    if (heartbeatMs > 0 && writer != null) {
                        WireWriter.sendQuiet(writer, ProtoLifecycle.heartbeat(host.nowMillis() - start));
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
                            // Any in-band line while a job runs is noise: cancellation arrives
                            // out-of-band as CANCEL_REQUEST on its own connection, or as EOF here.
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
                    WireWriter.sendQuiet(writer, cancelledTerminalLine(workspaceStream, eventDir));
                }
                // Release the plan slot before request-finish so status SSE carries the post-finish
                // activeBuildPlans count — Live activity finishes in the same frame.
                if (plan) host.noteBuildPlanFinished();
                JsonOut finishPayload = JsonOut.object()
                        .put("schema", 1)
                        .put("type", "request-finish")
                        .put("jid", eventRequestId)
                        .put("kind", eventKind)
                        .put("dir", eventDir)
                        .put("projectId", ProjectIds.idOf(eventDir))
                        .put("success", success)
                        .put("cancelled", cancelled)
                        .put("millis", elapsedMillis)
                        .put("activeBuildPlans", host.activeBuildPlans());
                String cancelReason = finishAcc != null ? finishAcc.cancelReason() : null;
                if (cancelled && cancelReason != null) finishPayload.put("cancelReason", cancelReason);
                host.publishEvent(
                        "request-finish",
                        host.withProgress(host.withIo(finishPayload, eventRequestId), eventRequestId));
                // Journal first: clearProgress retires the JobSession (drops the accumulator).
                // Writing after retire leaves a permanent running=true stub in jk jobs.
                try {
                    host.writeJournal(eventRequestId, cancelled, elapsedMillis, writer);
                } finally {
                    // Last write under the project's target/ is the journal's jk-results.md copy,
                    // so this is the moment the engine is provably done with the tree. The client
                    // blocks on this line rather than the plan terminal — otherwise `jk build`
                    // returns mid-write and a following `jk clean` races the memo/journal writers.
                    // In a finally so a throwing journal can never strand the client.
                    if (writer != null) WireWriter.sendQuiet(writer, ProtoLifecycle.jobFinish(eventRequestId));
                }
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
     * A run-scoped {@link RunNotices} note: one WARN line on the request's stream (step {@code ""},
     * code {@code "notice"}) and the SSE feed, redacted like every other event that leaves the
     * engine. Invoked from whatever thread noticed — the wire writer and SSE hub both take
     * concurrent writers.
     */
    private void publishNotice(long id, String dir, @Nullable BufferedWriter writer, String message) {
        String safe = EventRedaction.redactEnv(dir, message);
        if (writer != null) WireWriter.sendQuiet(writer, ProtoEvents.warn(dir, "", "notice", safe));
        host.publishEvent(
                "warn",
                JsonOut.object()
                        .put("schema", 1)
                        .put("type", "warn")
                        .put("jid", id)
                        .put("dir", dir)
                        .put("step", "")
                        .put("code", "notice")
                        .put("message", safe));
    }

    /**
     * User / EOF cancel: set cooperative flag and shut down workers with a short
     * grace→force window on a helper thread so the connection reader is not blocked. Idempotent.
     *
     * <p>Stamps the accumulator as user-cancelled immediately so a force-killed runner that never
     * emits userCancelled is journaled as cancelled, not as a truncated success/failure.
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
            // Workers first (SIGTERM → grace → SIGKILL), then interrupt the runner so
            // the scheduler does not join the rest of the DAG.
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

    /**
     * Wake the connection thread off client-readLine so it can run the finish tail.
     *
     * <p>Half-closing the read direction is the gentle wake: the blocked read sees EOF while the
     * write direction stays usable, so the tail can still deliver {@code job-finish} — the line the
     * client waits for before it may delete {@code target/}. {@link Thread#interrupt} is
     * the fallback, and it is blunt: on a thread blocked in an InterruptibleChannel read it closes
     * the whole channel, so the client learns the job ended one journal-write too early. A platform
     * whose half-close does not wake a blocked read is still covered — the client half-closes its
     * own end once it has the terminal, which delivers the same EOF.
     */
    private static void wakeOffClientRead(@Nullable SocketChannel channel, Thread connectionThread) {
        if (channel != null) {
            try {
                channel.shutdownInput();
                return;
            } catch (IOException | UnsupportedOperationException ignored) {
                // Not a half-closable transport (or already gone) — fall through to the blunt wake.
            }
        }
        try {
            connectionThread.interrupt();
        } catch (RuntimeException ignored) {
            // best-effort wake
        }
    }

    public void registerLiveJob(
            long jid,
            Session.CancelToken token,
            AtomicReference<Thread> runnerRef,
            @Nullable BufferedWriter writer,
            @Nullable SocketChannel channel,
            @Nullable Thread connectionThread,
            String dir,
            String kind,
            boolean workspaceStream) {
        liveJobs.put(jid, new LiveJob(token, runnerRef, writer, channel, connectionThread, dir, kind, workspaceStream));
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
        // without waiting behind that monitor. Order inside the task still matters: terminal
        // first, then the wake — a half-close where the transport allows it, so the write side
        // stays open for the job-finish the client blocks on.
        Thread.ofVirtual().name("jk-cancel-settle-" + jid).start(() -> {
            pushCancelledTerminal(job);
            if (job.connectionThread() != null) wakeOffClientRead(job.channel(), job.connectionThread());
        });
        return true;
    }

    private void pushCancelledTerminal(LiveJob job) {
        if (job.writer() == null) return;
        WireWriter.sendQuiet(job.writer(), cancelledTerminalLine(job.workspaceStream(), job.dir()));
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

    /**
     * A job thread that died without ruling. The throwable goes to the engine log with its stack,
     * and a terminal settles the client's stream: {@code request-failed} is the one line both the
     * single-plan and the workspace decoder end on, so it is right whatever shape was streaming.
     *
     * <p>Both halves matter. {@code catch (Exception)} at a verb boundary cannot see an
     * {@link Error}, and a job that ends with no terminal at all leaves the client at a bare EOF —
     * which it can only report as an engine that may have crashed, however healthy the engine is.
     */
    private void reportDeadJob(long jid, @Nullable String dir, @Nullable BufferedWriter writer, Throwable t) {
        String summary = t.getClass().getName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        host.log("jk engine: job " + jid + " died: " + summary + System.lineSeparator() + stackOf(t));
        if (writer == null) return;
        WireWriter.sendQuiet(
                writer,
                ProtoLifecycle.requestFailed(EventRedaction.redactEnv(
                        dir, "the build engine hit an internal error and could not finish: " + summary)));
    }

    private static String stackOf(Throwable t) {
        StringWriter rendered = new StringWriter();
        t.printStackTrace(new PrintWriter(rendered, true));
        return rendered.toString();
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

    /**
     * Stamp the cancel <em>and</em> why, so the journal can name who stopped the run. {@code
     * cancelled=true} alone reads the same for a Ctrl-C and for a wall deadline, and only the
     * deadline recorded a reason — the flag that already tells the two user paths apart
     * is the one that picks the sentence, so there is one mapping rather than a literal per caller.
     */
    private void markUserCancelled(long requestId, boolean explicit) {
        BuildAccumulator a = host.accumulatorOf(requestId);
        if (a != null) a.markUserCancelled(explicit, explicit ? CANCEL_BY_USER : CANCEL_BY_DISCONNECT);
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
        // Reason rides the accumulator so a job with no wire writer (HTTP/MCP) still journals WHY
        // it was cancelled and request-finish can carry it — the ERR_DEADLINE line below is
        // wire-only.
        BuildAccumulator a = host.accumulatorOf(eventRequestId);
        if (a != null) {
            a.markUserCancelled(
                    true, "exceeded the " + deadlineMs + "ms wall deadline (JK_ENGINE_JOB_DEADLINE_MS); cancelled");
        }
        int killed = JobWorkers.shutdownForRequest(eventRequestId, JobWorkers.cancelGraceMs());
        interruptRunner(runnerThread);
        WireWriter.sendQuiet(
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
     * request with an accumulator we trust an explicit stamp from CANCEL_REQUEST / mid-job EOF /
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
}
