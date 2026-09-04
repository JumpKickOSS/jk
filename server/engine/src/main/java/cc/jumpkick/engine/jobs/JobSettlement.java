// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.engine.JsonOut;
import cc.jumpkick.engine.WireWriter;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.runtime.ProjectIds;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.BufferedWriter;
import org.jspecify.annotations.Nullable;

/**
 * Compute one job's final verdict and emit its terminal effects in the required order: the
 * cancelled-terminal safety net, the plan slot release, {@code request-finish} carrying the
 * post-release plan count, the journal write, {@code job-finish} in a {@code finally} so a throwing
 * journal cannot strand the client, then {@code clearProgress} and the idle boundary. Uses all four
 * ports because settlement is where every concern of a job meets.
 */
final class JobSettlement {

    private final PlanSlots plans;
    private final JobEvents events;
    private final JobJournaling journal;
    private final JobRuntime runtime;

    JobSettlement(PlanSlots plans, JobEvents events, JobJournaling journal, JobRuntime runtime) {
        this.plans = plans;
        this.events = events;
        this.journal = journal;
        this.runtime = runtime;
    }

    /**
     * Settle {@code jid}. {@code rawCancelled} is the cancel token's word, which also trips on the
     * benign end-of-request EOF; {@link #effectiveCancelled} decides what it means.
     */
    void settle(
            long jid,
            String kind,
            String dir,
            boolean plan,
            boolean workspaceStream,
            @Nullable BufferedWriter writer,
            long startMillis,
            boolean rawCancelled) {
        long elapsedMillis = runtime.nowMillis() - startMillis;
        // cancelToken.cancelled also trips on the benign end-of-request EOF, so a finished
        // build (success or failure) can look cancelled. Correct it once here for both the
        // dashboard event and the journal.
        boolean cancelled = effectiveCancelled(jid, rawCancelled);
        // Same default as BuildAccumulator.toRecord — always emit success.
        BuildAccumulator finishAcc = journal.accumulatorOf(jid);
        boolean success = finishAcc != null ? finishAcc.effectiveSuccess(cancelled) : !cancelled;
        // Pin 100% only on success — a failed build keeps its last true percent, matching the
        // workspace-runner path and the stated policy.
        if (success && !cancelled) events.putLastProgress(jid, 100.0);
        // Safety net: if the runner was abandoned/interrupted without a terminal
        // wire event, still tell the CLI the job was cancelled so it does not report a crash.
        // Harmless if the runner already sent workspace-/plan-finish (client has returned).
        if (cancelled && writer != null) {
            // Same shape rule as pushCancelledTerminal: single builds journal as "build" but
            // their client loop only ends on plan-finish.
            WireWriter.sendQuiet(writer, LiveJobRegistry.cancelledTerminalLine(workspaceStream, dir));
        }
        // Release the plan slot before request-finish so status SSE carries the post-finish
        // activeBuildPlans count — Live activity finishes in the same frame.
        if (plan) plans.noteBuildPlanFinished();
        JsonOut finishPayload = JsonOut.object()
                .put("schema", 1)
                .put("type", "request-finish")
                .put("jid", jid)
                .put("kind", kind)
                .put("dir", dir)
                .put("projectId", ProjectIds.idOf(dir))
                .put("success", success)
                .put("cancelled", cancelled)
                .put("millis", elapsedMillis)
                .put("activeBuildPlans", plans.activeBuildPlans());
        String cancelReason = finishAcc != null ? finishAcc.cancelReason() : null;
        if (cancelled && cancelReason != null) finishPayload.put("cancelReason", cancelReason);
        events.publishEvent("request-finish", events.withProgress(events.withIo(finishPayload, jid), jid));
        // Journal first: clearProgress retires the JobSession (drops the accumulator).
        // Writing after retire leaves a permanent running=true stub in jk jobs.
        try {
            journal.writeJournal(jid, cancelled, elapsedMillis, writer);
        } finally {
            // Last write under the project's target/ is the journal's jk-results.md copy,
            // so this is the moment the engine is provably done with the tree. The client
            // blocks on this line rather than the plan terminal — otherwise `jk build`
            // returns mid-write and a following `jk clean` races the memo/journal writers.
            // In a finally so a throwing journal can never strand the client.
            if (writer != null) WireWriter.sendQuiet(writer, ProtoLifecycle.jobFinish(jid));
        }
        events.clearProgress(jid);
        // Idle boundary after finish side-effects so prune/GC see journal + event garbage too.
        // Cache maintenance (plan=false) only GCs when nothing else is in flight.
        if (plan) runtime.maybeIdleBoundary();
        else runtime.maybeIdleGc();
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
    boolean effectiveCancelled(long requestId, boolean rawCancelled) {
        BuildAccumulator a = journal.accumulatorOf(requestId);
        if (a == null) return rawCancelled;
        if (a.wasCancelled()) return true;
        return rawCancelled && !a.hasOutcome();
    }
}
