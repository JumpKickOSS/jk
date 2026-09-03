// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.engine.jobs.JobSessions;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.JournalWriter;
import cc.jumpkick.engine.listen.BridgingPlanListener;
import cc.jumpkick.engine.listen.BridgingWorkspaceListener;
import cc.jumpkick.engine.listen.CompositeEventSink;
import cc.jumpkick.engine.listen.EventSink;
import cc.jumpkick.engine.listen.SseEventSink;
import cc.jumpkick.engine.listen.WireEventSink;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.TimelineEvent;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.WorkModel;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Listener factories over the one event spine: producers emit {@link
 * cc.jumpkick.engine.listen.EngineEvent}; the sink is {@link WireEventSink} + {@link SseEventSink}
 * composed (or SSE alone for a detached job with no socket writer). Hooks carry only the engine
 * folds an event cannot: progress trackers, journal accumulation, timeline/slot teardown, capped
 * diagnostics.
 */
@RequiredArgsConstructor
public final class EngineListeners {

    private final JobSessions sessions;
    private final SsePublisher sse;
    private final JournalWriter journal;
    private final InFlightBuilds inFlight;
    private final LongSupplier eventRequestId;

    /** Workspace listener for one request; {@code writer} null = detached (HTTP/MCP) job. */
    public WorkspaceBuildListener workspace(@Nullable BufferedWriter writer, String workspaceDir) {
        long rid = eventRequestId.getAsLong();
        if (rid > 0 && workspaceDir != null) sessions.progressRoot(rid, workspaceDir);
        return new BridgingWorkspaceListener(workspaceDir, sinkFor(writer, rid), workspaceHooks(rid, writer));
    }

    /**
     * Plan listener for one hosted single-plan run. {@code realBuildPlan} is non-null only for a
     * single-project build — its {@code TEST_RESULT}/{@code BUILD_OUTCOME} keys ride along on
     * {@link EngineProtocol#BUILDPLAN_FINISH}.
     */
    public BuildPlanListener plan(String dir, @Nullable BufferedWriter writer, @Nullable BuildPlan realBuildPlan) {
        return hostedPlan(dir, writer, result -> encodePlanFinish(dir, realBuildPlan, result), realBuildPlan != null);
    }

    /** As {@link #plan(String, BufferedWriter, BuildPlan)} with a pluggable terminal encoder. */
    public BuildPlanListener plan(
            String dir, @Nullable BufferedWriter writer, Function<BuildPlanResult, String> finishEncoder) {
        return hostedPlan(dir, writer, finishEncoder, false);
    }

    public void flushTimeline(long requestId, @Nullable BufferedWriter writer) {
        BuildAccumulator a = sessions.accumulator(requestId);
        if (a == null) return;
        a.flushTimeline().ifPresent(path -> {
            if (writer != null) WireWriter.sendQuiet(writer, new TimelineEvent(path.toString()).encode());
        });
    }

    private EventSink sinkFor(@Nullable BufferedWriter writer, long rid) {
        SseEventSink sseSink = new SseEventSink(sse, rid);
        return writer == null ? sseSink : new CompositeEventSink(new WireEventSink(writer), sseSink);
    }

    private BridgingWorkspaceListener.Hooks workspaceHooks(long rid, @Nullable BufferedWriter writer) {
        return new BridgingWorkspaceListener.Hooks() {
            @Override
            public void preflight(String stage, int done, int total) {
                if (rid > 0) {
                    sessions.tracker(rid).preflight(stage, done, total);
                    sse.emitWorkspaceProgress(rid, writer, true);
                }
            }

            @Override
            public void workModel(WorkModel model) {
                if (rid <= 0) return;
                sessions.remaining(rid, model.toRemainingWork());
                sessions.tracker(rid).seedWall(model.R0(), model.costs().size());
                sse.emitWorkspaceProgress(rid, writer, true);
            }

            @Override
            public void recordWeight(String dir, long weight) {
                if (rid > 0) sessions.weights(rid).put(dir, weight);
            }

            @Override
            public void planWeights(long totalWeight, int modules) {
                if (rid > 0) {
                    sessions.tracker(rid).calibrate(totalWeight, modules);
                    sse.emitWorkspaceProgress(rid, writer, true);
                }
            }

            @Override
            public void moduleGraph(Map<Path, Set<Path>> prereqs) {
                journal.accModuleGraph(rid, prereqs);
            }

            @Override
            public void moduleFinished(ModuleOutcome o) {
                journal.accModule(rid, o);
            }

            @Override
            public void trackModule(String dir, BuildPlanView view) {
                sse.trackModuleBuildPlan(rid, dir, view, writer, false);
            }

            @Override
            public void trackModuleComplete(String dir, long lastDen) {
                sse.trackModuleComplete(rid, dir, lastDen, writer);
            }

            @Override
            public BridgingPlanListener.Hooks planHooks(String dir) {
                return EngineListeners.this.planHooks(rid, dir, writer, false);
            }

            @Override
            public void testsFrom(BuildPlan plan) {
                journal.accTests(rid, plan.get(BuildPlanner.TEST_RESULT).orElse(null));
                journal.accAffected(rid, plan.get(BuildPlanner.AFFECTED_TESTS).orElse(null));
            }
        };
    }

    private BridgingPlanListener.Hooks planHooks(
            long rid, String dir, @Nullable BufferedWriter writer, boolean releaseSlotOnFinish) {
        return new BridgingPlanListener.Hooks() {
            @Override
            public void stepFinished(String d, String step, String phase, String status, long millis) {
                journal.accStepFinish(rid, d, step, phase, status, millis);
            }

            @Override
            public void planFinished(String d, BuildPlanResult result) {
                flushTimeline(rid, writer);
                if (releaseSlotOnFinish) inFlight.release(rid);
                journal.accBuildPlanFinish(rid, d, result);
            }

            @Override
            public void planDiagnostics(String d, BuildPlanResult result) {
                if (!result.success()) sse.publishDiagnostics(rid, d, result.errors());
            }
        };
    }

    private BuildPlanListener hostedPlan(
            String dir,
            @Nullable BufferedWriter writer,
            @Nullable Function<BuildPlanResult, String> finishEncoder,
            boolean releaseSlotOnFinish) {
        long rid = eventRequestId.getAsLong();
        return new CoalescingBuildPlanListener(new BridgingPlanListener(
                dir, sinkFor(writer, rid), planHooks(rid, dir, writer, releaseSlotOnFinish), finishEncoder));
    }

    /**
     * Terminal {@code plan-finish} for a single-project build/test. Wire {@code cancelled} is
     * user/deadline cancel only — {@link BuildPlanResult#cancelled()} is also set on cooperative
     * fail-fast and must not look like the user cancelled the job.
     */
    static String encodePlanFinish(String dir, @Nullable BuildPlan realBuildPlan, BuildPlanResult result) {
        TestSummary testResult = realBuildPlan == null
                ? null
                : realBuildPlan.get(BuildPlanner.TEST_RESULT).orElse(null);
        String buildOutcome = realBuildPlan == null
                ? null
                : realBuildPlan.get(BuildPlanner.BUILD_OUTCOME).orElse(null);
        boolean cancelled = result.userCancelled();
        if (testResult == null && buildOutcome == null) {
            return ProtoEvents.planFinish(dir, result.success(), cancelled);
        }
        return ProtoEvents.withCancelled(
                ProtoEvents.planFinish(
                        dir,
                        result.success(),
                        buildOutcome,
                        testResult != null ? testResult.total() : -1,
                        testResult != null ? testResult.succeeded() : -1,
                        testResult != null ? testResult.failed() : -1,
                        testResult != null ? testResult.skipped() : -1),
                cancelled);
    }
}
