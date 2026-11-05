// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.builds.ProjectIds;
import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.api.WireWriter;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.listen.EventRedaction;
import cc.jumpkick.wire.protocol.ProtoLifecycle;
import java.io.BufferedWriter;
import org.jspecify.annotations.Nullable;

/**
 * Compute one job's final verdict and emit its terminal effects in the required order: the
 * cancelled-terminal safety net, the journal write, then in a {@code finally} so a throwing journal
 * cannot strand the client or the drain: the plan slot release, {@code request-finish} carrying the
 * post-release plan count, {@code job-finish} and {@code clearProgress}; the idle boundary last.
 * Uses all four ports because settlement is where every concern of a job meets.
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
        // Built before the journal write, which takes the accumulator the byte counters live on.
        // activeBuildPlans is set again once the slot is released; the key keeps its place.
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
        JsonOut finishEvent = events.withProgress(events.withIo(finishPayload, jid), jid);
        String warning = success && !cancelled ? configWarning(jid, dir) : null;
        String noop = null;
        try {
            // The record is written while the job still holds its plan slot: a draining engine
            // exits once no slot is held, so no jobs in flight means every record written.
            noop = journal.writeJournal(jid, cancelled, elapsedMillis, writer);
        } finally {
            // Released before request-finish, so the event carries the post-finish count and Live
            // activity settles in the same frame.
            if (plan) plans.noteBuildPlanFinished();
            finishEvent.put("activeBuildPlans", plans.activeBuildPlans());
            events.publishEvent("request-finish", finishEvent);
            // Last write under the project's target/ is the journal's jk-results.md copy,
            // so this is the moment the engine is provably done with the tree. The client
            // blocks on this line rather than the plan terminal — otherwise `jk build`
            // returns mid-write and a following `jk clean` races the memo/journal writers.
            // In a finally so a throwing journal can never strand the client.
            // A no-op says so: its run has no directory, so the client keeps no transcript for it.
            if (writer != null) WireWriter.sendQuiet(writer, ProtoLifecycle.jobFinish(jid, noop, warning));
            // After the journal: clearProgress retires the JobSession (drops the accumulator), and
            // an MCP wait counts the job live until its session retires.
            events.clearProgress(jid);
        }
        // Idle boundary after finish side-effects so prune/GC see journal + event garbage too.
        // Cache maintenance (plan=false) only GCs when nothing else is in flight.
        if (plan) runtime.maybeIdleBoundary();
        else runtime.maybeIdleGc();
    }

    /**
     * A succeeded job beside a {@code ~/.jk/config.toml} that does not parse: every reader that
     * would have used the file fails, so this job read nothing from it and stands, but the user
     * hears of the break now rather than on the next resolve. Decided here, once per request and
     * for every verb, because only the settled verdict knows the job succeeded; a job that failed
     * on the file already names it. The dashboard and MCP get the same note as a {@code warn}.
     */
    private @Nullable String configWarning(long jid, String dir) {
        String safe;
        try {
            String raw = runtime.userConfigWarning().orElse(null);
            if (raw == null) return null;
            safe = EventRedaction.redactText(dir, raw);
        } catch (RuntimeException e) {
            // A note must never cost the client its job-finish line.
            runtime.log("jk engine: config.toml check failed: " + e);
            return null;
        }
        events.publishEvent(
                "warn",
                JsonOut.object()
                        .put("schema", 1)
                        .put("type", "warn")
                        .put("jid", jid)
                        .put("dir", dir)
                        .put("step", "")
                        .put("code", USER_CONFIG_UNPARSED)
                        .put("message", safe));
        return safe;
    }

    /** The {@code warn} code of {@link #configWarning}. */
    static final String USER_CONFIG_UNPARSED = "user-config-unparsed";

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
