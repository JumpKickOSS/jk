// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.listen;

import cc.jumpkick.engine.api.CoalescingBuildPlanListener;
import cc.jumpkick.plugin.build.InvocationPhase;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.BuildPlanView;
import cc.jumpkick.run.Task;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkModel;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * One workspace listener over one {@link EventSink} (wire + SSE composed; SSE alone for a
 * detached job). Engine folds that are not events (progress trackers, journal) live on
 * {@link Hooks}.
 */
public final class BridgingWorkspaceListener implements WorkspaceBuildListener {

    public interface Hooks {
        default void preflight(String stage, int done, int total) {}

        default void workModel(WorkModel model) {}

        default void recordWeight(String dir, long weight) {}

        default void planWeights(long totalWeight, int modules) {}

        default void moduleGraph(Map<Path, Set<Path>> prereqs) {}

        default void moduleFinished(ModuleOutcome o) {}

        default void trackModule(String dir, BuildPlanView view) {}

        default void trackModuleComplete(String dir, long lastDen) {}

        default BridgingPlanListener.Hooks planHooks(String dir) {
            return new BridgingPlanListener.Hooks() {};
        }

        default void testsFrom(BuildPlan plan) {}
    }

    private final EventSink sink;
    private final Hooks hooks;
    private final Map<String, BuildPlan> moduleBuildPlans = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastDenByDir = new ConcurrentHashMap<>();

    public BridgingWorkspaceListener(@Nullable String workspaceDir, EventSink sink, Hooks hooks) {
        this.sink = sink;
        this.hooks = hooks;
    }

    @Override
    public void onPreflight(String stage, int done, int total, String label) {
        sink.emit(new EngineEvent.Preflight(stage, done, total, label));
        InvocationPhase inv =
                switch (stage == null ? "" : stage) {
                    case "lock", "graph" -> InvocationPhase.RESOLVE;
                    case "checking", "plan", "prepare", "calibrate" -> InvocationPhase.PLAN;
                    default -> null;
                };
        if (inv != null) {
            String status = (total > 0 && done >= total) ? "finish" : "start";
            sink.emit(new EngineEvent.InvocationPhase(inv.wireName(), status));
        }
        hooks.preflight(stage == null ? "" : stage, done, total);
    }

    @Override
    public void onWorkModel(WorkModel model) {
        if (model == null) return;
        hooks.workModel(model);
    }

    @Override
    public void onPlan(List<ModulePlan> plan) {
        long totalWeight = 0;
        for (ModulePlan m : plan) {
            String dir = m.dir().toString();
            totalWeight += m.weight();
            hooks.recordWeight(dir, m.weight());
            sink.emit(new EngineEvent.PlanModule(dir, m.coord(), m.plan().name(), m.weight(), m.fullyCached()));
            for (Task p : m.plan().steps()) {
                sink.emit(new EngineEvent.PlanStep(
                        dir,
                        p.name(),
                        p.label(),
                        BridgingPlanListener.phaseWire(p.group().orElse(null))));
            }
        }
        sink.emit(new EngineEvent.PlanDone(plan.size()));
        sink.emit(new EngineEvent.Plan(totalWeight, plan.size()));
        hooks.planWeights(totalWeight, plan.size());
    }

    @Override
    public void onModuleGraph(Map<Path, Set<Path>> prereqs) {
        hooks.moduleGraph(prereqs);
    }

    @Override
    public void onEtaEstimate(long remainingMs) {
        sink.emit(new EngineEvent.Eta(remainingMs));
    }

    @Override
    public BuildPlanListener onModuleStart(ModulePlan m) {
        String dir = m.dir().toString();
        moduleBuildPlans.put(dir, m.plan());
        sink.emit(new EngineEvent.ModuleStart(dir, m.coord()));
        BridgingPlanListener.Hooks nested = hooks.planHooks(dir);
        BridgingPlanListener.Hooks planHooks = new BridgingPlanListener.Hooks() {
            @Override
            public void planProgress(String d, BuildPlanView view) {
                lastDenByDir.put(d, view.denominator());
                hooks.trackModule(d, view);
                nested.planProgress(d, view);
            }

            @Override
            public void stepFinished(String d, String step, String phase, String status, long millis, long waitMillis) {
                nested.stepFinished(d, step, phase, status, millis, waitMillis);
            }

            @Override
            public void planFinished(String d, BuildPlanResult result) {
                nested.planFinished(d, result);
            }

            @Override
            public void planDiagnostics(String d, BuildPlanResult result) {
                nested.planDiagnostics(d, result);
            }
        };
        return new CoalescingBuildPlanListener(new BridgingPlanListener(dir, sink, planHooks));
    }

    @Override
    public void onModuleFinish(ModuleOutcome o) {
        String dir = o.dir().toString();
        hooks.trackModuleComplete(dir, lastDenByDir.getOrDefault(dir, 0L));
        sink.emit(new EngineEvent.ModuleFinish(
                dir, o.coord(), o.success(), o.exitCode(), o.millis(), o.didWork(), o.cancelled(), o.image()));
        hooks.moduleFinished(o);
        BuildPlan g = moduleBuildPlans.remove(dir);
        if (g != null) hooks.testsFrom(g);
    }
}
