// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.api.BuildJobFingerprint;
import cc.jumpkick.engine.api.WireWriter;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.host.Log;
import cc.jumpkick.wire.protocol.ProtoEvents;
import java.io.BufferedWriter;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Which jids are cancellable right now, and how to cancel one. A remote cancel acks without
 * waiting: the cancelled terminal and the connection wake run on a {@code jk-cancel-settle} thread,
 * terminal first, so a stream writer wedged on a client that is not draining cannot hold the ack.
 * Its only host collaborators are the accumulator lookup and the log.
 */
public final class LiveJobRegistry {

    /**
     * Why a cancelled row was cancelled, for the two signals this envelope can tell apart. Both
     * ride the journal as the {@code cancelled} warning diagnostic and {@code request-finish}'s
     * {@code cancelReason}, next to the wall deadline's own sentence in {@link JobWatchdog#enforceDeadline}.
     */
    private static final String CANCEL_BY_USER = "cancelled by the user (Ctrl-C, jk cancel, or the dashboard)";

    private static final String CANCEL_BY_DISCONNECT = "the client disconnected before the job finished";

    private final ConcurrentHashMap<Long, LiveJob> liveJobs = new ConcurrentHashMap<>();
    private final Function<Long, @Nullable BuildAccumulator> accumulatorOf;
    private final Consumer<String> log;
    private final long cancelGraceMs;

    /** @param cancelGraceMs the shared worker SIGTERM-to-SIGKILL window a remote cancel uses */
    LiveJobRegistry(
            Function<Long, @Nullable BuildAccumulator> accumulatorOf, Consumer<String> log, long cancelGraceMs) {
        this.accumulatorOf = accumulatorOf;
        this.log = log;
        this.cancelGraceMs = cancelGraceMs;
    }

    /**
     * User / EOF cancel: set cooperative flag and shut down workers with a short
     * grace→force window on a helper thread so the connection reader is not blocked. Idempotent.
     *
     * <p>Stamps the accumulator as user-cancelled immediately so a force-killed runner that never
     * emits userCancelled is journaled as cancelled, not as a truncated success/failure, and
     * releases the job's {@link LiveJob#cancelSignal()} so its joiner bounds the rest of the wait.
     */
    public void beginUserCancel(
            long eventRequestId,
            Session.CancelToken cancelToken,
            @Nullable AtomicReference<Thread> runnerRef,
            long cancelGraceMs,
            boolean explicit) {
        cancelToken.cancel();
        markUserCancelled(eventRequestId, explicit);
        LiveJob job = liveJobs.get(eventRequestId);
        if (job != null) job.cancelSignal().countDown();
        // Cancel runs after the request thread may be gone; the token and request id are explicit.
        Thread.ofVirtual().name("jk-cancel-" + eventRequestId, 0).start(() -> {
            // Workers first (SIGTERM → grace → SIGKILL), then interrupt the runner so
            // the scheduler does not join the rest of the DAG.
            int killed = JobWorkers.shutdownForRequest(eventRequestId, cancelGraceMs);
            interruptRunner(runnerRef != null ? runnerRef.get() : null);
            if (killed > 0) {
                log.accept("jk engine: cancel job "
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
            @Nullable SocketChannel channel,
            @Nullable Thread connectionThread,
            CountDownLatch cancelSignal,
            String dir,
            String kind,
            boolean workspaceStream) {
        liveJobs.put(
                jid,
                new LiveJob(
                        token, runnerRef, writer, channel, connectionThread, cancelSignal, dir, kind, workspaceStream));
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
        beginUserCancel(jid, job.token(), job.runnerRef(), cancelGraceMs, true);
        // Terminal + reader wake happen off-thread: the job's stream writer can be wedged in a
        // socket write (client not draining), and `jk cancel` / POST /api/cancel must ack
        // without waiting behind that monitor. Order inside the task still matters: terminal
        // first, then the wake — a half-close where the transport allows it, so the write side
        // stays open for the job-finish the client blocks on.
        // Settles the cancelled terminal off the request thread; reads no session.
        Thread.ofVirtual().name("jk-cancel-settle-" + jid).start(() -> {
            pushCancelledTerminal(job);
            if (job.connectionThread() != null)
                ConnectionWatch.wakeOffClientRead(job.channel(), job.connectionThread());
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
        BuildAccumulator a = accumulatorOf.apply(requestId);
        if (a != null) a.markUserCancelled(explicit, explicit ? CANCEL_BY_USER : CANCEL_BY_DISCONNECT);
    }

    static void interruptRunner(@Nullable Thread runnerThread) {
        if (runnerThread == null) return;
        try {
            runnerThread.interrupt();
        } catch (RuntimeException e) {
            // best-effort
            Log.debug("interruptRunner: best-effort", e);
        }
    }
}
