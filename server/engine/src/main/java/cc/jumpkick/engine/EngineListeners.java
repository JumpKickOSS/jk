// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.engine.jobs.JobSessions;
import cc.jumpkick.engine.journal.BuildAccumulator;
import cc.jumpkick.engine.journal.JournalWriter;
import cc.jumpkick.engine.listen.BridgingPlanListener;
import cc.jumpkick.engine.listen.BridgingWorkspaceListener;
import cc.jumpkick.engine.listen.EventSink;
import cc.jumpkick.engine.listen.NoopEventSink;
import cc.jumpkick.engine.listen.WireEventSink;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.runtime.ModuleOutcome;
import cc.jumpkick.runtime.WorkspaceBuildListener;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/** CLI / HTTP listener factories: wire sink plus SSE and journal hooks. */
public final class EngineListeners {

    private final JobSessions sessions;
    private final SsePublisher sse;
    private final JournalWriter journal;
    private final InFlightBuilds inFlight;
    private final LongSupplier eventRequestId;

    public EngineListeners(
            JobSessions sessions,
            SsePublisher sse,
            JournalWriter journal,
            InFlightBuilds inFlight,
            LongSupplier eventRequestId) {
        this.sessions = sessions;
        this.sse = sse;
        this.journal = journal;
        this.inFlight = inFlight;
        this.eventRequestId = eventRequestId;
    }

    public WorkspaceBuildListener wire(BufferedWriter writer, String workspaceDir) {
        return workspace(workspaceDir, new WireEventSink(writer), writer);
    }

    public WorkspaceBuildListener hub(String workspaceDir) {
        return workspace(workspaceDir, NoopEventSink.INSTANCE, null);
    }

    /**
     * Translate every {@link BuildPlanListener} callback for one plan into a {@code dir}-tagged wire
     * event. {@code realBuildPlan} is non-null only for a single-project run — its
     * {@code TEST_RESULT}/{@code BUILD_OUTCOME} keys ride along on
     * {@link EngineProtocol#BUILDPLAN_FINISH}; {@code null} for a per-module workspace plan.
     */
    public BuildPlanListener wirePlan(String dir, BufferedWriter writer, @Nullable BuildPlan realBuildPlan) {
        return hostedPlan(
                dir,
                new WireEventSink(writer),
                writer,
                result -> encodePlanFinish(dir, realBuildPlan, result),
                realBuildPlan != null);
    }

    /**
     * As {@link #wirePlan(String, BufferedWriter, BuildPlan)}, with a pluggable terminal encoder
     * — how lock/update/sync ride their summary counts on the same plan-finish message.
     */
    public BuildPlanListener wirePlan(
            String dir, BufferedWriter writer, Function<BuildPlanResult, String> finishEncoder) {
        return hostedPlan(dir, new WireEventSink(writer), writer, finishEncoder, false);
    }

    /** HTTP lock: same hooks as CLI, no JSONL writer. */
    public BuildPlanListener hubPlan(String dir) {
        return hostedPlan(dir, NoopEventSink.INSTANCE, null, null, false);
    }

    public void flushTimeline(long requestId, @Nullable BufferedWriter writer) {
        BuildAccumulator a = sessions.accumulator(requestId);
        if (a == null) return;
        a.flushTimeline().ifPresent(path -> {
            if (writer != null) EngineServer.sendQuiet(writer, EngineProtocol.timeline(path.toString()));
        });
    }

    private WorkspaceBuildListener workspace(String workspaceDir, EventSink sink, @Nullable BufferedWriter writer) {
        long rid = eventRequestId.getAsLong();
        if (rid > 0 && workspaceDir != null) sessions.progressRoot(rid, workspaceDir);
        return new BridgingWorkspaceListener(workspaceDir, sink, workspaceHooks(rid, writer));
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
            public void workModel(cc.jumpkick.runtime.WorkModel model) {
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
                sse.publishPlan(rid, totalWeight);
            }

            @Override
            public void moduleGraph(java.util.Map<Path, java.util.Set<Path>> prereqs) {
                journal.accModuleGraph(rid, prereqs);
            }

            @Override
            public void eta(long remainingMs) {
                sse.publishEta(rid, remainingMs);
            }

            @Override
            public void moduleStarted(String dir, String coord) {
                sse.publishModuleStart(rid, dir, coord);
            }

            @Override
            public void moduleFinished(ModuleOutcome o) {
                journal.accModule(rid, o);
                sse.publishModuleFinish(rid, o.dir().toString(), o.coord(), o.success(), o.millis(), o.didWork());
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
            public void testsFrom(cc.jumpkick.run.BuildPlan plan) {
                journal.accTests(
                        rid,
                        plan.get(cc.jumpkick.runtime.BuildPlanner.TEST_RESULT).orElse(null));
            }
        };
    }

    private BridgingPlanListener.Hooks planHooks(
            long rid, String dir, @Nullable BufferedWriter writer, boolean releaseSlotOnFinish) {
        return new BridgingPlanListener.Hooks() {
            @Override
            public void planProgress(String d, BuildPlanView view) {
                sse.publishBuildPlanProgress(rid, d, view);
            }

            @Override
            public void stepStarted(String d, String step, String phase) {
                sse.publishStepStart(rid, d, step, phase);
            }

            @Override
            public void stepFinished(String d, String step, String phase, String status, long millis) {
                journal.accStepFinish(rid, d, step, phase, status, millis);
                sse.publishStepFinish(rid, d, step, phase, status, millis);
            }

            @Override
            public void labeled(String d, String step, String text) {
                sse.publishLabel(rid, d, step, text);
            }

            @Override
            public void output(String d, String step, String line) {
                sse.publishOutput(rid, d, step, line);
            }

            @Override
            public void planFinished(String d, BuildPlanResult result) {
                flushTimeline(rid, writer);
                if (releaseSlotOnFinish) inFlight.release(rid);
                journal.accBuildPlanFinish(rid, d, result);
                sse.publishBuildPlanFinish(rid, d, result.success());
                if (!result.success()) sse.publishDiagnostics(rid, d, result.errors());
            }
        };
    }

    private BuildPlanListener hostedPlan(
            String dir,
            EventSink sink,
            @Nullable BufferedWriter writer,
            @Nullable Function<BuildPlanResult, String> finishEncoder,
            boolean releaseSlotOnFinish) {
        return new CoalescingBuildPlanListener(new BridgingPlanListener(
                dir, sink, planHooks(eventRequestId.getAsLong(), dir, writer, releaseSlotOnFinish), finishEncoder));
    }

    /**
     * Terminal {@code plan-finish} for a single-project build/test. Wire {@code cancelled} is
     * user/deadline cancel only — {@link BuildPlanResult#cancelled()} is also set on cooperative
     * fail-fast and must not look like the user cancelled the job.
     */
    static String encodePlanFinish(String dir, @Nullable BuildPlan realBuildPlan, BuildPlanResult result) {
        TestSummary testResult = realBuildPlan == null
                ? null
                : realBuildPlan
                        .get(cc.jumpkick.runtime.BuildPlanner.TEST_RESULT)
                        .orElse(null);
        String buildOutcome = realBuildPlan == null
                ? null
                : realBuildPlan
                        .get(cc.jumpkick.runtime.BuildPlanner.BUILD_OUTCOME)
                        .orElse(null);
        boolean cancelled = result.userCancelled();
        if (testResult == null && buildOutcome == null) {
            return EngineProtocol.planFinish(dir, result.success(), cancelled);
        }
        return EngineProtocol.withCancelled(
                EngineProtocol.planFinish(
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
