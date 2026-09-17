// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.config.JobLimits;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.api.WireWriter;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.BufferedWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Heartbeats from {@link JobLimits}, one {@link WallDeadline}, and a stall note for one job. A
 * heartbeat is a wire line that resets the client's stream idle timer, so a detached (HTTP/MCP)
 * job with no writer runs only the deadline and stall arms. The stall arm writes one engine log
 * line, naming the job and its live workers, when the job has emitted no task event for {@link
 * #STALL_NOTE_MS}, and again after every further silence of that length; it never cancels — a
 * single test JVM working through a long suite is silent and healthy. Its host collaborators are
 * the clock, the accumulator lookup, the session's last-event stamp and the log.
 */
final class JobWatchdog {

    /** Silence after which a live job is named in the engine log: thirty minutes. */
    static final long STALL_NOTE_MS = 30 * 60_000L;

    private final JobLimits limits;
    private final LongSupplier nowMillis;
    private final Function<Long, @Nullable BuildAccumulator> accumulatorOf;
    private final LongUnaryOperator lastEventAt;
    private final Consumer<String> log;
    private final long stallNoteMs;

    JobWatchdog(
            JobLimits limits,
            LongSupplier nowMillis,
            Function<Long, @Nullable BuildAccumulator> accumulatorOf,
            LongUnaryOperator lastEventAt,
            Consumer<String> log) {
        this(limits, nowMillis, accumulatorOf, lastEventAt, log, STALL_NOTE_MS);
    }

    /** As above with the stall silence supplied — a test names a job after milliseconds. */
    JobWatchdog(
            JobLimits limits,
            LongSupplier nowMillis,
            Function<Long, @Nullable BuildAccumulator> accumulatorOf,
            LongUnaryOperator lastEventAt,
            Consumer<String> log,
            long stallNoteMs) {
        this.limits = limits;
        this.nowMillis = nowMillis;
        this.accumulatorOf = accumulatorOf;
        this.lastEventAt = lastEventAt;
        this.log = log;
        this.stallNoteMs = stallNoteMs;
    }

    /**
     * Start the watchdog for a job admitted at {@code startMillis} that counts {@code done} down
     * when it ends, or return {@code null} when no arm is live. The deadline runs from the job's
     * admission — the one start the join and the journal also measure from — not from when this
     * thread happens to run. On deadline: cancel, worker shutdown (grace then force), interrupt the
     * runner, and one {@code error} line for the client. On silence: one log line naming the job.
     */
    @Nullable
    Thread start(
            long eventRequestId,
            String kind,
            String dir,
            Session.CancelToken cancelToken,
            AtomicReference<Thread> runnerRef,
            CountDownLatch done,
            @Nullable BufferedWriter writer,
            WallDeadline deadline,
            long startMillis) {
        Watch watch = watch(eventRequestId, kind, dir, cancelToken, runnerRef, writer, deadline, startMillis);
        if (watch == null) return null;
        // Holds the job's cancel token explicitly; reads no session.
        return Thread.ofVirtual().name("jk-job-watchdog", 0).start(() -> {
            while (done.getCount() > 0) {
                long wait = watch.nextWait();
                if (wait < 0) {
                    watch.deadlinePassed();
                    return;
                }
                try {
                    if (done.await(wait, TimeUnit.MILLISECONDS)) return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (done.getCount() == 0) return;
                watch.pass();
            }
        });
    }

    /**
     * One job's watch — what a tick does and how long the next one waits — or {@code null} when no
     * arm is live. The thread {@link #start} runs is the loop around it; a test drives the passes
     * itself against a clock it advances, so nothing it asserts waits on the wall.
     */
    @Nullable
    Watch watch(
            long eventRequestId,
            String kind,
            String dir,
            Session.CancelToken cancelToken,
            AtomicReference<Thread> runnerRef,
            @Nullable BufferedWriter writer,
            WallDeadline deadline,
            long startMillis) {
        boolean heartbeats = limits.heartbeatMs() > 0 && writer != null;
        boolean stalls = stallNoteMs > 0;
        if (!(heartbeats || deadline.bounded() || stalls)) return null;
        return new Watch(eventRequestId, kind, dir, cancelToken, runnerRef, writer, deadline, startMillis, heartbeats);
    }

    /** The arms of one job's watch and the stamp the stall arm carries from pass to pass. */
    final class Watch {
        private final long eventRequestId;
        private final String kind;
        private final String dir;
        private final Session.CancelToken cancelToken;
        private final AtomicReference<Thread> runnerRef;
        private final @Nullable BufferedWriter writer;
        private final WallDeadline deadline;
        private final long start;
        private final boolean heartbeats;
        private long lastNoted;

        private Watch(
                long eventRequestId,
                String kind,
                String dir,
                Session.CancelToken cancelToken,
                AtomicReference<Thread> runnerRef,
                @Nullable BufferedWriter writer,
                WallDeadline deadline,
                long start,
                boolean heartbeats) {
            this.eventRequestId = eventRequestId;
            this.kind = kind;
            this.dir = dir;
            this.cancelToken = cancelToken;
            this.runnerRef = runnerRef;
            this.writer = writer;
            this.deadline = deadline;
            this.start = start;
            this.heartbeats = heartbeats;
            this.lastNoted = start;
        }

        /**
         * Milliseconds until the next pass, or a negative number once the deadline has passed. The
         * heartbeat sets the tick only when there is a stream to keep alive; a deadline-only watch
         * re-reads the clock every second, a stall-only watch at a fraction of the silence it is
         * looking for.
         */
        long nextWait() {
            long wait = heartbeats ? limits.heartbeatMs() : deadline.bounded() ? 1_000L : stallTick();
            if (deadline.bounded()) {
                long remaining = deadline.ms() - (nowMillis.getAsLong() - start);
                if (remaining <= 0) return -1L;
                wait = Math.min(wait, remaining);
            }
            return wait;
        }

        /** One pass after a tick: the heartbeat line, then the stall note when the job has been silent long enough. */
        void pass() {
            if (heartbeats) {
                WireWriter.sendQuiet(writer, ProtoLifecycle.heartbeat(nowMillis.getAsLong() - start));
            }
            if (stallNoteMs > 0) lastNoted = noteStall(eventRequestId, kind, dir, start, lastNoted);
        }

        /** The deadline arm: cancel the job and tell the client. */
        void deadlinePassed() {
            enforceDeadline(eventRequestId, cancelToken, runnerRef.get(), writer, deadline);
        }
    }

    private long stallTick() {
        return Math.max(1_000L, Math.min(60_000L, stallNoteMs / 2));
    }

    /**
     * Log the job when it has been silent for {@link #stallNoteMs} since its last task event, its
     * start, or the last note; returns the stamp the next silence is measured from.
     */
    private long noteStall(long jid, String kind, String dir, long start, long lastNoted) {
        long now = nowMillis.getAsLong();
        long basis = Math.max(Math.max(lastEventAt.applyAsLong(jid), start), lastNoted);
        if (now - basis < stallNoteMs) return lastNoted;
        int workers = JobWorkers.liveCountForRequest(jid);
        log.accept("jk engine: job " + jid + " (" + kind + " " + dir + ") has emitted no task event for "
                + JobRow.duration(now - Math.max(lastEventAt.applyAsLong(jid), start)) + "; "
                + workers + (workers == 1 ? " worker process" : " worker processes") + " alive, "
                + "the job is still live and holds its memory share — `jk cancel " + jid + "` stops it");
        return now;
    }

    /**
     * Wall-deadline kill: cooperative cancel + worker grace→force + interrupt
     * runner. Idempotent; safe from the watchdog and the connection thread. Stamps the accumulator so
     * deadline-truncated wall-clock never trains ETA (same as user cancel).
     */
    void enforceDeadline(
            long eventRequestId,
            Session.CancelToken cancelToken,
            @Nullable Thread runnerThread,
            @Nullable BufferedWriter writer,
            WallDeadline deadline) {
        cancelToken.cancel();
        // Reason rides the accumulator so a job with no wire writer (HTTP/MCP) still journals WHY
        // it was cancelled and request-finish can carry it — the ERR_DEADLINE line below is
        // wire-only.
        BuildAccumulator a = accumulatorOf.apply(eventRequestId);
        if (a != null) a.markUserCancelled(true, deadline.reason());
        int killed = JobWorkers.shutdownForRequest(eventRequestId, limits.cancelGraceMs());
        LiveJobRegistry.interruptRunner(runnerThread);
        WireWriter.sendQuiet(
                writer,
                ProtoLifecycle.error(
                        EngineProtocol.ERR_DEADLINE,
                        "job exceeded " + deadline.ms() + "ms (" + deadline.knob() + "); cancelled"
                                + (killed > 0 ? " (killed " + killed + " worker process(es))" : "")));
    }
}
