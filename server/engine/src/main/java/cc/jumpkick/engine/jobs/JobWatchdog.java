// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.config.JobLimits;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.JobWorkers;
import cc.jumpkick.engine.WireWriter;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.BufferedWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Heartbeats and one wall deadline for one job, from {@link JobLimits}. A heartbeat is a wire line
 * that resets the client's stream idle timer, so a detached (HTTP/MCP) job with no writer runs only
 * the deadline arm — and with no deadline either, no thread starts at all. Its only host
 * collaborators are the clock and the accumulator lookup.
 */
final class JobWatchdog {

    private final JobLimits limits;
    private final LongSupplier nowMillis;
    private final Function<Long, @Nullable BuildAccumulator> accumulatorOf;

    JobWatchdog(JobLimits limits, LongSupplier nowMillis, Function<Long, @Nullable BuildAccumulator> accumulatorOf) {
        this.limits = limits;
        this.nowMillis = nowMillis;
        this.accumulatorOf = accumulatorOf;
    }

    /**
     * Start the watchdog for a job that counts {@code done} down when it ends, or return {@code null}
     * when neither arm is live. On deadline: cancel, worker shutdown (grace then force), interrupt the
     * runner, and one {@code error} line for the client.
     */
    @Nullable
    Thread start(
            long eventRequestId,
            Session.CancelToken cancelToken,
            AtomicReference<Thread> runnerRef,
            CountDownLatch done,
            @Nullable BufferedWriter writer) {
        long heartbeatMs = limits.heartbeatMs();
        long deadlineMs = limits.deadlineMs();
        if (!((heartbeatMs > 0 && writer != null) || deadlineMs > 0)) return null;
        return Thread.ofVirtual().name("jk-job-watchdog", 0).start(() -> {
            long start = nowMillis.getAsLong();
            while (done.getCount() > 0) {
                long elapsed = nowMillis.getAsLong() - start;
                long wait = heartbeatMs > 0 ? heartbeatMs : 1_000L;
                if (deadlineMs > 0) {
                    long remaining = deadlineMs - elapsed;
                    if (remaining <= 0) {
                        enforceDeadline(eventRequestId, cancelToken, runnerRef.get(), writer);
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
                    WireWriter.sendQuiet(writer, ProtoLifecycle.heartbeat(nowMillis.getAsLong() - start));
                }
            }
        });
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
            @Nullable BufferedWriter writer) {
        long deadlineMs = limits.deadlineMs();
        cancelToken.cancel();
        // Reason rides the accumulator so a job with no wire writer (HTTP/MCP) still journals WHY
        // it was cancelled and request-finish can carry it — the ERR_DEADLINE line below is
        // wire-only.
        BuildAccumulator a = accumulatorOf.apply(eventRequestId);
        if (a != null) {
            a.markUserCancelled(
                    true, "exceeded the " + deadlineMs + "ms wall deadline (JK_ENGINE_JOB_DEADLINE_MS); cancelled");
        }
        int killed = JobWorkers.shutdownForRequest(eventRequestId, limits.cancelGraceMs());
        LiveJobRegistry.interruptRunner(runnerThread);
        WireWriter.sendQuiet(
                writer,
                ProtoLifecycle.error(
                        EngineProtocol.ERR_DEADLINE,
                        "job exceeded "
                                + deadlineMs
                                + "ms (JK_ENGINE_JOB_DEADLINE_MS); cancelled"
                                + (killed > 0 ? " (killed " + killed + " worker process(es))" : "")));
    }
}
