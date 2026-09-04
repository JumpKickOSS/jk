// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.compile.JavaCompilerHost;
import cc.jumpkick.config.JobLimits;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.BuildJobFingerprint;
import cc.jumpkick.engine.InFlightBuilds;
import cc.jumpkick.engine.JobWorkers;
import cc.jumpkick.engine.JsonOut;
import cc.jumpkick.engine.WireWriter;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.InputTrees;
import cc.jumpkick.model.command.Exit;
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
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
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

    /** {@code limits} come from the engine's resolved config; the envelope never reads the environment. */
    public JobEnvelope(Host host, JobLimits limits) {
        this.host = host;
        this.limits = limits;
        this.watchdogs = new JobWatchdog(limits, host::nowMillis, host::accumulatorOf);
        this.live = new LiveJobRegistry(host::accumulatorOf, host::log);
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
        ConnectionWatch watch = new ConnectionWatch(host::nowMillis, host::log);
        Thread connectionThread = Thread.currentThread();
        live.registerLiveJob(
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
                live.unregisterLiveJob(eventRequestId);
                host.inFlight().release(eventRequestId);
                done.countDown();
                // Unblock the connection thread only if it is parked on client readLine
                // waiting for EOF — remote cancel finishes the runner without
                // the client writing anything. Only while actually parked: a wake that lands
                // after the read loop poisons teardown I/O instead (a stray interrupt once killed
                // journal completion with ClosedByInterruptException, leaving a permanent
                // "running" job in jk jobs).
                if (!detached) watch.wakeIfParked(channel, connectionThread);
            }
        });
        runnerRef.set(started);
        started.start(); // register live job + runnerRef before start
        long cancelGraceMs = JobWorkers.cancelGraceMs();
        final Thread watchdog = watchdogs.start(eventRequestId, cancelToken, runnerRef, done, writer);
        Runnable finish = () -> {
            try {
                watch.watchForEof(
                        reader,
                        done,
                        () -> live.beginUserCancel(eventRequestId, cancelToken, runnerRef, cancelGraceMs, false));
                watch.awaitRunner(
                        eventRequestId,
                        done,
                        limits,
                        cancelGraceMs,
                        eventStartMillis,
                        cancelToken.cancelled(),
                        () -> watchdogs.enforceDeadline(eventRequestId, cancelToken, runnerRef.get(), writer),
                        () -> {
                            JobWorkers.shutdownForRequest(eventRequestId, 0L);
                            LiveJobRegistry.interruptRunner(runnerRef.get());
                        });
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

    /** Cancel one live job by jid; {@code false} when unknown or already finished. */
    public boolean cancelJob(long jid) {
        return live.cancelJob(jid);
    }

    /** Cancel every live job whose dir matches (canonical absolute path). */
    public int cancelJobsForDir(String dir) {
        return live.cancelJobsForDir(dir);
    }

    /** Whether a job's cancel token means a real cancel — see {@link JobSettlement#effectiveCancelled}. */
    public boolean effectiveCancelled(long requestId, boolean rawCancelled) {
        return settlement.effectiveCancelled(requestId, rawCancelled);
    }

    /** Test seam: the registry, for driving cancels the way the wire does. */
    LiveJobRegistry live() {
        return live;
    }

    public static String journalDir(String requestLine) {
        String dir = Jsonl.str(requestLine, "dir");
        if (dir != null) return dir;
        String cache = Jsonl.str(requestLine, "cache");
        if (cache != null) return cache;
        return "";
    }
}
