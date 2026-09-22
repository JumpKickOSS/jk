// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.builds.ProjectIds;
import cc.jumpkick.compile.JavaCompilerHost;
import cc.jumpkick.config.JobLimits;
import cc.jumpkick.config.RequestScope;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.api.BuildJobFingerprint;
import cc.jumpkick.engine.api.InFlightBuilds;
import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.api.WireWriter;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.InputTrees;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.runtime.base.LiveUnits;
import cc.jumpkick.task.IoLedger;
import cc.jumpkick.task.RunNotices;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProtoJobs;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * One job lifecycle for CLI, HTTP and MCP submissions. Admit, heartbeat, cancel, deadline, and
 * request-finish live here as one story — do not split the race comments into hooks.
 */
public final class JobEnvelope {

    /**
     * Process-side callbacks the envelope must not own, as the union of the four ports the
     * extracted phases take: {@link PlanSlots}, {@link JobEvents}, {@link JobJournaling} and
     * {@link JobRuntime}. One implementation wires all four; each phase asks for only the ports it uses.
     */
    public interface Host extends PlanSlots, JobEvents, JobJournaling, JobRuntime {}

    private final Host host;
    private final JobLimits limits;
    private final JobWatchdog watchdogs;
    private final LiveJobRegistry live;
    private final JobSettlement settlement;
    private final MemoryAdmission admission;

    /** {@code limits} come from the engine's resolved config; the envelope never reads the environment. */
    public JobEnvelope(Host host, JobLimits limits) {
        this(host, limits, MemoryAdmission.forRuntime(limits.queueWaitMs(), host::nowMillis));
    }

    /** As above with the memory gate supplied — tests hand in a fake heap and a fixed per-job cost. */
    public JobEnvelope(Host host, JobLimits limits, MemoryAdmission admission) {
        this.host = host;
        this.limits = limits;
        this.admission = admission;
        this.watchdogs = new JobWatchdog(limits, host::nowMillis, host::accumulatorOf, host::lastEventAt, host::log);
        this.live = new LiveJobRegistry(host::accumulatorOf, host::log, limits.cancelGraceMs());
        this.settlement = new JobSettlement(host, host, host, host);
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
     * sink) and throws {@link AlreadyRunning} / {@link IllegalStateException} on refusal. The wall
     * deadline is the transport's: EOF bounds a socket job, so it runs under the engine-wide cap
     * ({@code 0} by default); nothing but the clock bounds a detached job, so it runs under the
     * submission's own deadline or the engine's detached default.
     */
    public long submit(String requestLine, JobRequest job, JobTransport transport) {
        BufferedReader reader = transport instanceof JobTransport.SocketWatch w ? w.reader() : null;
        BufferedWriter writer = transport instanceof JobTransport.SocketWatch w ? w.writer() : null;
        boolean detached = transport instanceof JobTransport.FireAndForget;
        WallDeadline deadline = WallDeadline.of(limits, transport);
        String threadPrefix = job.threadPrefix();
        String kind = job.verb();
        JobBody runner = job.body();
        boolean plan = job.joinsActivePlans();
        boolean workspaceStream = job.workspaceTerminal();
        // Refuse new jobs while draining. The listener is already closed, so this is the race
        // on a connection accepted just before yield, or an already-open session.
        if (host.draining()) return refuseDraining(detached, writer);
        // The kind rides explicitly from the dispatch site (never parsed back out of a thread
        // name); the journal dir falls back to a request's specific location field so non-build
        // requests never record the literal string "null".
        String eventKind = kind;
        String eventDir = journalDir(requestLine);
        // exclusive fingerprint + start-time build number for journaled kinds. A same-fingerprint
        // job already running is refused here, never queued behind itself; the definitive claim
        // is JobAdmit's, below.
        String fingerprint = BuildJobFingerprint.ofRequest(eventKind, requestLine);
        if (BuildJobFingerprint.isExclusiveKind(eventKind) && fingerprint != null && !fingerprint.isEmpty()) {
            var running = host.inFlight().peek(fingerprint);
            if (running.isPresent()) return refuseAlreadyRunning(running.get(), eventKind, detached, false, writer);
        }
        long eventRequestId = host.nextRequestId();
        boolean claimedBuildPlanSlot = false;
        if (plan) {
            Long refused = admitPlan(eventRequestId, eventKind, eventDir, workspaceStream, detached, writer);
            if (refused != null) return refused;
            claimedBuildPlanSlot = true;
        }
        Session.CancelToken cancelToken = Session.CancelToken.live();
        CountDownLatch done = new CountDownLatch(1);
        // Released by the first user cancel: the joiner parks on the runner's end and on this,
        // so a cancel that reaches a body wedged past its interrupt still ends in a bounded join.
        CountDownLatch cancelSignal = new CountDownLatch(1);
        // The requesting shell's JK_PROGRESS_MODE rides the request — the resident engine's own
        // startup env is not the client's.
        host.putMode(eventRequestId, ProtoJobs.progressModeOf(requestLine));
        long eventStartMillis = host.nowMillis();
        boolean rebuildRun = Jsonl.bool(requestLine, "rebuild", false) || Jsonl.bool(requestLine, "force", false);
        // Who asked: default "cli"; optimize/calibrate mark synthetic history. The session (an MCP
        // connection, an IDE window) rides beside it when the requester has one.
        String trigger = Jsonl.str(requestLine, "trigger");
        if (trigger == null || trigger.isBlank()) trigger = "cli";
        String session = Jsonl.str(requestLine, "session");
        AdmitResult admit = JobAdmit.admit(host, eventRequestId, eventKind, eventDir, fingerprint, trigger, session);
        if (admit.rejected() != null) {
            admission.release(eventRequestId);
            return refuseAlreadyRunning(admit.rejected(), eventKind, detached, claimedBuildPlanSlot, writer);
        }
        host.publishRequestStart(eventRequestId, eventKind, eventDir, admit.buildNumber());
        host.registerAccumulator(
                eventRequestId,
                eventKind,
                eventDir,
                trigger,
                session,
                !job.kind().writesTimeline() || Jsonl.bool(requestLine, "noTimeline", false),
                rebuildRun,
                admit.buildNumber(),
                admit.journalId());
        // The slot was already claimed above, atomically with the shutdown check.
        AtomicReference<Thread> runnerRef = new AtomicReference<>();
        // Public jid surface — client tracks this for Ctrl-C / jk cancel.
        if (writer != null) {
            try {
                WireWriter.send(writer, JobAdmit.jobStartLine(host, eventRequestId, eventKind, eventDir, admit));
            } catch (IOException ignored) {
                // client gone before job body — still run cancel registration below
            }
        }
        // A detached run has no CLI to open its transcript: the engine writes the header itself.
        if (detached) JobAdmit.openDetachedTranscript(host, eventKind, eventDir, trigger, session, admit);
        ConnectionWatch watch = new ConnectionWatch(host::nowMillis, host::log);
        live.registerLiveJob(
                eventRequestId,
                cancelToken,
                runnerRef,
                writer,
                cancelSignal,
                eventDir,
                eventKind,
                eventStartMillis,
                workspaceStream);
        Admitted admitted = new Admitted(
                requestLine,
                runner,
                plan,
                detached,
                eventRequestId,
                eventKind,
                eventDir,
                eventStartMillis,
                workspaceStream,
                cancelToken,
                done,
                cancelSignal,
                runnerRef,
                writer,
                watch,
                deadline,
                new AtomicBoolean());
        // The job body binds its own session inside the verb (SessionContext.where); unstarted so runnerRef is set
        // first.
        Thread started = Thread.ofVirtual().name(threadPrefix, 0).unstarted(() -> runBody(admitted));
        runnerRef.set(started);
        started.start(); // register live job + runnerRef before start
        final Thread watchdog = watchdogs.start(
                eventRequestId, eventKind, eventDir, cancelToken, runnerRef, done, writer, deadline, eventStartMillis);
        Runnable finish = () -> finish(admitted, reader, watchdog);
        if (detached) {
            // Joins and journals the detached job; reads no session.
            Thread.ofVirtual().name("jk-job-join-", 0).start(finish);
            return eventRequestId;
        }
        try {
            finish.run();
        } finally {
            // The client blocks on job-finish; it has landed — however the tail ended — before
            // the connection loop gets the socket back and may close it.
            if (writer != null) WireWriter.awaitLanded(writer);
        }
        return eventRequestId;
    }

    /**
     * Coordinator memory first, then the plan slot. A queued job is not a live plan — a drain does
     * not wait for it and status does not count it — and the slot claim stays the atomic
     * not-draining check: shutdown can never observe zero plans for a job about to start. {@code
     * null} once the slot is claimed; otherwise the refusal already sent, for {@code submit} to
     * return.
     */
    private @Nullable Long admitPlan(
            long jid,
            String kind,
            String dir,
            boolean workspaceStream,
            boolean detached,
            @Nullable BufferedWriter writer) {
        MemoryAdmission.Verdict verdict = admission.admit(
                jid,
                kind,
                dir,
                (ahead, waitedMs) -> announceQueued(jid, kind, dir, ahead, waitedMs, writer),
                host::draining);
        switch (verdict) {
            case CANCELLED -> {
                return refuseCancelledInQueue(jid, kind, dir, workspaceStream, detached, writer);
            }
            case DRAINING -> {
                return refuseDraining(detached, writer);
            }
            case TIMED_OUT -> {
                return refuseTimedOut(jid, kind, dir, detached, writer);
            }
            case TOO_LARGE -> {
                return refuseTooLarge(jid, kind, dir, detached, writer);
            }
            case ADMITTED -> {
                /* fall through to the slot claim */
            }
        }
        if (!host.tryStartBuildPlan()) {
            admission.release(jid);
            return refuseDraining(detached, writer);
        }
        LastBuiltRoot.note(dir);
        return null;
    }

    /**
     * The job has to wait for coordinator memory: a {@code job-queued} line naming its position and
     * the live jobs, so the client can say who it waits on and keeps the jid as its cancel handle.
     * The first one ({@code waitedMs == 0}) also sends the {@code request-queued} frame that paints
     * the dashboard's queued card and writes one log line for the post-mortem; the later ones are
     * the wire's alone, which also keeps the client's stream-idle timer from firing while it waits.
     */
    private void announceQueued(
            long jid, String kind, String dir, int ahead, long waitedMs, @Nullable BufferedWriter writer) {
        List<JobRow> liveRows = liveRows();
        if (writer != null) {
            WireWriter.sendQuiet(writer, ProtoLifecycle.jobQueued(jid, ahead, waitedMs, JobRow.toWire(liveRows)));
        }
        if (waitedMs > 0) return;
        host.publishRequestQueued(jid, kind, dir, ahead);
        host.log("jk engine: job " + jid + " (" + kind + " " + dir + ") waits for engine memory behind " + ahead
                + (ahead == 1 ? " job" : " jobs") + liveSummary(liveRows));
    }

    /** {@code ; live: test /app (jid 739) since 22:36} for a log line or an error, {@code ""} with no live job. */
    private static String liveSummary(List<JobRow> liveRows) {
        if (liveRows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("; live: ");
        for (int i = 0; i < liveRows.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(liveRows.get(i).describe());
        }
        return sb.toString();
    }

    /**
     * Waited the engine's queue-wait bound without being admitted: the client gets an {@code
     * error} naming the jobs ahead, the live job holding the heap and the knob, instead of a
     * connection that closes with no result; the dashboard card resolves through {@code
     * request-finish}. Nothing ran, so no journal row was ever begun.
     */
    private long refuseTimedOut(long jid, String kind, String dir, boolean detached, @Nullable BufferedWriter writer) {
        int ahead = admission.queued();
        String message =
                "gave up after waiting " + JobRow.duration(admission.timing().queueWaitMs())
                        + " for engine memory behind "
                        + ahead + (ahead == 1 ? " job" : " jobs") + liveSummary(liveRows())
                        + " — `jk cancel <jid>` frees a live job, `jk engine status` lists them;"
                        + " [engine] queue-wait-ms / JK_ENGINE_QUEUE_WAIT_MS sets the wait";
        return refuse(jid, kind, dir, detached, writer, EngineProtocol.ERR_QUEUE_WAIT, message);
    }

    /**
     * The job's estimate exceeds what the engine's heap could ever hold: refused before it starts,
     * naming the estimate, the cap and the knob, since admitting it would end in the
     * OutOfMemoryError exit that takes every other job with it. Nothing ran; no journal row began.
     */
    private long refuseTooLarge(long jid, String kind, String dir, boolean detached, @Nullable BufferedWriter writer) {
        long estimate = admission.estimateFor(kind, dir);
        long cap = admission.heapMaxBytes();
        long needed = mib(estimate + MemoryAdmission.RESERVE_BYTES);
        String message = "a " + kind + " of " + dir + " is estimated to need " + mib(estimate)
                + " MiB of engine heap and the engine's cap is " + mib(cap)
                + " MiB — set [engine] max-heap-mb in ~/.jk/config.toml (or JK_ENGINE_MAX_HEAP_MB) to at least "
                + needed + ", then `jk engine stop`; the next command starts the engine under the new cap";
        return refuse(jid, kind, dir, detached, writer, EngineProtocol.ERR_ENGINE_HEAP, message);
    }

    /** Whole mebibytes, rounded up. */
    private static long mib(long bytes) {
        return (bytes + (1L << 20) - 1) >> 20;
    }

    /** A refusal before the job ran: one log line, the dashboard card resolved, the error to the client. */
    private long refuse(
            long jid,
            String kind,
            String dir,
            boolean detached,
            @Nullable BufferedWriter writer,
            String code,
            String message) {
        host.log("jk engine: job " + jid + " (" + kind + " " + dir + ") " + message);
        host.publishEvent(
                "request-finish",
                JsonOut.object()
                        .put("schema", 1)
                        .put("type", "request-finish")
                        .put("jid", jid)
                        .put("kind", kind)
                        .put("dir", dir)
                        .put("projectId", ProjectIds.idOf(dir))
                        .put("success", false)
                        .put("cancelled", false)
                        .put("error", message)
                        .put("millis", 0L)
                        .put("activeBuildPlans", host.activeBuildPlans()));
        if (detached) throw new IllegalStateException(message);
        // A refusal's terminal has landed before submit returns, as a job's finish has: the
        // connection loop may close the socket the moment it gets it back.
        if (writer != null) {
            WireWriter.sendQuiet(writer, ProtoLifecycle.error(code, message));
            WireWriter.awaitLanded(writer);
        }
        return -1;
    }

    /**
     * Cancelled while waiting: nothing ran, so the stream ends on the cancelled terminal its shape
     * expects and the dashboard card resolves through {@code request-finish} — no journal row was
     * ever begun for it.
     */
    private long refuseCancelledInQueue(
            long jid,
            String kind,
            String dir,
            boolean workspaceStream,
            boolean detached,
            @Nullable BufferedWriter writer) {
        host.publishEvent(
                "request-finish",
                JsonOut.object()
                        .put("schema", 1)
                        .put("type", "request-finish")
                        .put("jid", jid)
                        .put("kind", kind)
                        .put("dir", dir)
                        .put("projectId", ProjectIds.idOf(dir))
                        .put("success", false)
                        .put("cancelled", true)
                        .put("millis", 0L)
                        .put("activeBuildPlans", host.activeBuildPlans()));
        if (detached) throw new IllegalStateException("cancelled while waiting for engine memory");
        if (writer != null) {
            WireWriter.sendQuiet(writer, LiveJobRegistry.cancelledTerminalLine(workspaceStream, dir));
            WireWriter.awaitLanded(writer);
        }
        return -1;
    }

    /** The engine is draining: a detached submit throws, a socket submit tells the client and returns -1. */
    private static long refuseDraining(boolean detached, @Nullable BufferedWriter writer) {
        if (detached) throw new IllegalStateException("engine is shutting down");
        try {
            if (writer != null)
                WireWriter.send(
                        writer,
                        ProtoLifecycle.error(
                                EngineProtocol.ERR_SHUTTING_DOWN,
                                "the engine is shutting down (draining) — retry; the successor engine takes over"));
            if (writer != null) WireWriter.awaitLanded(writer);
        } catch (IOException ignored) {
            // Client vanished mid-refusal — nothing to do; the connection is closing anyway.
        }
        return -1;
    }

    /** A same-fingerprint job holds the slot: give back the plan slot nothing ran on, then refuse. */
    private long refuseAlreadyRunning(
            InFlightBuilds.Hold h,
            String eventKind,
            boolean detached,
            boolean claimedBuildPlanSlot,
            @Nullable BufferedWriter writer) {
        String label = "test".equals(eventKind) ? "Test" : "Build";
        String msg = label + (h.buildNumber() > 0 ? " #" + h.buildNumber() : "") + " is already running";
        if (detached) {
            if (claimedBuildPlanSlot) host.abandonBuildPlanSlot();
            throw new AlreadyRunning(msg, h.requestId(), h.buildNumber());
        }
        try {
            if (writer != null) {
                WireWriter.send(writer, ProtoLifecycle.alreadyRunning(h.buildNumber(), h.requestId(), msg));
                WireWriter.awaitLanded(writer);
            }
        } catch (IOException ignored) {
            // client gone
        }
        if (claimedBuildPlanSlot) host.abandonBuildPlanSlot(); // nothing ran — give the slot back
        return -1;
    }

    /**
     * One admitted job as its two threads see it: the request and body, the identity the journal
     * and the wire carry, the handles the runner thread signals and the connection thread waits on,
     * and the wall deadline it runs under.
     */
    private record Admitted(
            String requestLine,
            JobBody runner,
            boolean plan,
            boolean detached,
            long eventRequestId,
            String eventKind,
            String eventDir,
            long eventStartMillis,
            boolean workspaceStream,
            Session.CancelToken cancelToken,
            CountDownLatch done,
            CountDownLatch cancelSignal,
            AtomicReference<Thread> runnerRef,
            @Nullable BufferedWriter writer,
            ConnectionWatch watch,
            WallDeadline deadline,
            /**
             * Set once the body has returned or thrown — its terminal is on the wire and only the
             * teardown remains — so the connection thread reads a client's EOF after that as the
             * end of the request rather than a disconnect to cancel.
             */
            AtomicBoolean bodyDone) {}

    /** The runner thread: open the request's scopes, run the body, stamp the verdict, tear down. */
    private void runBody(Admitted a) {
        String requestLine = a.requestLine();
        JobBody runner = a.runner();
        boolean plan = a.plan();
        long eventRequestId = a.eventRequestId();
        String eventDir = a.eventDir();
        Session.CancelToken cancelToken = a.cancelToken();
        CountDownLatch done = a.done();
        BufferedWriter writer = a.writer();
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
            // Test runs and coverage published anywhere under this request land on its record.
            host.openResults(eventRequestId);
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
            a.bodyDone().set(true);
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
            RequestScope.release();
            IoLedger.close();
            host.closeResults();
            // Kill leftovers first, THEN drain the Zinc session: if the worker is mid-compile
            // its io thread is blocked in readLine and never sees end()'s POISON, so end() would
            // burn its full 15s join before this force-kill ran. Killing the process
            // first unblocks readLine, so end()'s join returns promptly.
            // Never clear() the registry without shutdown, or a racing cancel thread's
            // shutdownForRequest finds an empty set and plugin/javac children keep running.
            JobWorkers.shutdownForRequest(eventRequestId, 0L);
            JavaCompilerHost.end(eventRequestId);
            LiveUnits.end(eventRequestId);
            JobWorkers.close();
            host.unbindEventRequestId();
            if (plan) host.cacheGate().readLock().unlock();
            // Unregister the live job BEFORE releasing the in-flight hold: waiters watch the
            // hold, cancel watches liveJobs — this order means a job never looks finished
            // while cancelJob would still succeed. The hold still frees before the connection
            // thread's teardown so a follow-up same-project build is not rejected.
            live.unregisterLiveJob(eventRequestId);
            host.inFlight().release(eventRequestId);
            admission.release(eventRequestId);
            done.countDown();
        }
    }

    /**
     * The connection thread (or a detached joiner): watch the socket for EOF, wait for the runner
     * within the limits, then settle the request whatever happened.
     */
    private void finish(Admitted a, @Nullable BufferedReader reader, @Nullable Thread watchdog) {
        boolean plan = a.plan();
        long eventRequestId = a.eventRequestId();
        String eventKind = a.eventKind();
        String eventDir = a.eventDir();
        long eventStartMillis = a.eventStartMillis();
        boolean workspaceStream = a.workspaceStream();
        Session.CancelToken cancelToken = a.cancelToken();
        CountDownLatch done = a.done();
        AtomicReference<Thread> runnerRef = a.runnerRef();
        BufferedWriter writer = a.writer();
        ConnectionWatch watch = a.watch();
        long cancelGraceMs = limits.cancelGraceMs();
        try {
            watch.watchForEof(
                    reader,
                    done,
                    a.bodyDone()::get,
                    () -> live.beginUserCancel(eventRequestId, cancelToken, runnerRef, cancelGraceMs, false));
            watch.awaitRunner(
                    eventRequestId,
                    done,
                    a.deadline(),
                    limits.deadlineGraceMs(),
                    cancelGraceMs,
                    eventStartMillis,
                    a.cancelSignal(),
                    () -> watchdogs.enforceDeadline(eventRequestId, cancelToken, runnerRef.get(), writer, a.deadline()),
                    () -> {
                        JobWorkers.shutdownForRequest(eventRequestId, 0L);
                        LiveJobRegistry.interruptRunner(runnerRef.get());
                    });
            // The runner is abandoned: whatever it is still doing, the facts it memoized for this
            // request are released now rather than when — if ever — its thread reaches its own
            // teardown. Idempotent with that teardown.
            if (done.getCount() > 0) RequestScope.release(host.runIo(eventRequestId));
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
            // The runner finally releases the checkout slot after the body returns. Releasing it
            // here while that thread is still in the body admits the next build onto the same tree.
            // A runner that never started, or that died without counting down, still has to be released.
            Thread runner = runnerRef.get();
            if (done.getCount() == 0 || runner == null || !runner.isAlive()) {
                host.inFlight().release(eventRequestId);
            }
            admission.release(eventRequestId);
            settlement.settle(
                    eventRequestId,
                    eventKind,
                    eventDir,
                    plan,
                    workspaceStream,
                    writer,
                    eventStartMillis,
                    cancelToken.cancelled());
        }
    }

    /**
     * A run-scoped {@link RunNotices} note: one WARN line on the request's stream (step {@code ""},
     * code {@code "notice"}) and the SSE feed, redacted like every other event that leaves the
     * engine. Invoked from whatever thread noticed — the wire writer and SSE hub both take
     * concurrent writers.
     */
    private void publishNotice(long id, String dir, @Nullable BufferedWriter writer, String message) {
        String redacted = EventRedaction.redactEnv(dir, message);
        String safe = redacted == null ? message : redacted;
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
        String failure = "the build engine hit an internal error and could not finish: " + summary;
        String redactedFailure = EventRedaction.redactEnv(dir, failure);
        WireWriter.sendQuiet(writer, ProtoLifecycle.requestFailed(redactedFailure == null ? failure : redactedFailure));
    }

    private static String stackOf(Throwable t) {
        StringWriter rendered = new StringWriter();
        t.printStackTrace(new PrintWriter(rendered, true));
        return rendered.toString();
    }

    /** Cancel one live or queued job by jid; {@code false} when unknown or already finished. */
    public boolean cancelJob(long jid) {
        return live.cancelJob(jid) || admission.cancel(jid);
    }

    /** Cancel every live or queued job whose dir matches (canonical absolute path). */
    public int cancelJobsForDir(String dir) {
        return live.cancelJobsForDir(dir) + admission.cancelForDir(dir);
    }

    /** Jobs waiting for coordinator memory right now; the status vital behind {@code queuedBuildPlans}. */
    public int queued() {
        return admission.queued();
    }

    /** Every live job, oldest first, then every queued job in arrival order. */
    public List<JobRow> jobs() {
        List<JobRow> out = new ArrayList<>(liveRows());
        out.addAll(admission.queuedRows());
        return out;
    }

    /** {@link #jobs()} as the status vitals carry it. */
    public List<Map<String, Object>> jobsJson() {
        return JobRow.toJson(jobs());
    }

    private List<JobRow> liveRows() {
        return live.rows(JobWorkers::liveCountForRequest, host::lastEventAt);
    }

    /** Whether a job's cancel token means a real cancel — see {@link JobSettlement#effectiveCancelled}. */
    public boolean effectiveCancelled(long requestId, boolean rawCancelled) {
        return settlement.effectiveCancelled(requestId, rawCancelled);
    }

    /** Test seam: the registry, for driving cancels the way the wire does. */
    LiveJobRegistry live() {
        return live;
    }

    /** The directory a job is journaled and judged under: its {@code dir}, an import's {@code baseDir}, else its cache. */
    public static String journalDir(String requestLine) {
        String dir = Jsonl.str(requestLine, "dir");
        if (dir != null) return dir;
        String baseDir = Jsonl.str(requestLine, "baseDir");
        if (baseDir != null) return baseDir;
        String cache = Jsonl.str(requestLine, "cache");
        if (cache != null) return cache;
        return "";
    }
}
