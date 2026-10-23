// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.runtime.base.SiblingArtifacts;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A module forecast in its plan's terms: the steps the module's own {@link ModulePipeline} plan
 * holds, under the plan's names, each priced the way it declares ({@link StepForecast}). A verdict
 * for a step the plan does not hold is dropped, a step that rides with its module is never
 * reported, and an always-run step always runs. The module-level verdicts ({@code
 * restore-outputs}, {@code order-check}) stand beside the steps.
 */
final class PlanForecast {

    private PlanForecast() {}

    /** Each of {@code modules} shaped by its plan for {@code target} through its phase. */
    static List<TaskForecast.Module> shape(
            List<TaskForecast.Module> modules,
            BuildGraph.Result graph,
            Path cache,
            boolean skipTests,
            WorkspaceTarget target,
            @Nullable String profile) {
        Map<Path, BuildGraph.BuildUnit> units = new HashMap<>();
        Set<Path> moduleDirs = new LinkedHashSet<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) {
            units.put(u.dir(), u);
            moduleDirs.add(u.dir());
        }
        if (units.isEmpty()) return modules;
        WorkspaceRequest request = request(graph, cache, skipTests, target, profile);
        Set<Path> jarConsumed = WorkspacePreflightPhase.jarConsumed(graph);
        Set<Path> launched = WorkspacePreflightPhase.launchedAssemblies(graph);
        List<TaskForecast.Module> out = new ArrayList<>(modules.size());
        for (TaskForecast.Module m : modules) {
            BuildPlan plan = planOf(m, units, request, moduleDirs, jarConsumed, launched);
            out.add(plan == null ? m : shape(m, plan));
        }
        return out;
    }

    /**
     * Every verdict for a step the module's plan holds but declares as riding along, as {@code
     * coord step}: the walk prices it, so the declaration hides work the step does. A verdict for a
     * step the plan does not hold at all is the plan deciding, and {@link #shape} drops it.
     */
    static List<String> misdeclared(
            List<TaskForecast.Module> modules,
            BuildGraph.Result graph,
            Path cache,
            boolean skipTests,
            WorkspaceTarget target,
            @Nullable String profile) {
        Map<Path, BuildGraph.BuildUnit> units = new HashMap<>();
        Set<Path> moduleDirs = new LinkedHashSet<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) {
            units.put(u.dir(), u);
            moduleDirs.add(u.dir());
        }
        if (units.isEmpty()) return List.of();
        WorkspaceRequest request = request(graph, cache, skipTests, target, profile);
        Set<Path> jarConsumed = WorkspacePreflightPhase.jarConsumed(graph);
        Set<Path> launched = WorkspacePreflightPhase.launchedAssemblies(graph);
        List<String> out = new ArrayList<>();
        for (TaskForecast.Module m : modules) {
            BuildPlan plan = planOf(m, units, request, moduleDirs, jarConsumed, launched);
            if (plan == null) continue;
            Set<String> riding = new LinkedHashSet<>();
            for (Task t : plan.steps()) {
                if (t.forecast() == null || t.forecast() instanceof StepForecast.Rides) riding.add(t.name());
            }
            for (TaskForecast.Task v : m.steps()) {
                if (riding.contains(stepName(v.name()))) out.add(m.coord() + " " + v.name());
            }
        }
        return out;
    }

    private static WorkspaceRequest request(
            BuildGraph.Result graph, Path cache, boolean skipTests, WorkspaceTarget target, @Nullable String profile) {
        Path entry = graph.topoOrder().getLast().dir();
        return new WorkspaceRequest(entry, cache, null, 0, profile, skipTests, false, 0, null, false, false)
                .withSpec(WorkspaceSpec.of(target));
    }

    /** {@code m}'s plan, or null for a module the planner cannot plan: it keeps the walk's verdict. */
    private static @Nullable BuildPlan planOf(
            TaskForecast.Module m,
            Map<Path, BuildGraph.BuildUnit> units,
            WorkspaceRequest request,
            Set<Path> moduleDirs,
            Set<Path> jarConsumed,
            Set<Path> launched) {
        BuildGraph.BuildUnit unit = units.get(m.dir());
        if (unit == null) return null;
        try {
            return ModulePipeline.planned(
                            unit, request, moduleDirs, false, jarConsumed, launched, SiblingArtifacts.NONE)
                    .plan();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The plan's steps that are priced or always run, in plan order. */
    private static Map<String, StepForecast> declaredSteps(BuildPlan plan) {
        Map<String, StepForecast> declared = new LinkedHashMap<>();
        for (Task t : plan.steps()) {
            StepForecast how = t.forecast();
            if (how != null && !(how instanceof StepForecast.Rides)) declared.put(t.name(), how);
        }
        return declared;
    }

    /** {@code m}'s verdicts restricted to, and named as, the steps of {@code plan}. */
    static TaskForecast.Module shape(TaskForecast.Module m, BuildPlan plan) {
        Map<String, StepForecast> declared = declaredSteps(plan);
        Map<String, TaskForecast.Task> priced = new LinkedHashMap<>();
        List<TaskForecast.Task> moduleVerdicts = new ArrayList<>();
        for (TaskForecast.Task v : m.steps()) {
            if (isModuleVerdict(v.name())) {
                moduleVerdicts.add(v);
                continue;
            }
            String name = stepName(v.name());
            if (declared.containsKey(name)) {
                priced.putIfAbsent(
                        name, name.equals(v.name()) ? v : new TaskForecast.Task(name, v.status(), v.text(), v.key()));
            } else {
                Perf.note("forecast-not-in-plan " + m.coord() + " " + v.name(), "status", v.status());
            }
        }
        List<TaskForecast.Task> steps = new ArrayList<>();
        for (var e : declared.entrySet()) {
            TaskForecast.Task v = priced.get(e.getKey());
            if (e.getValue() instanceof StepForecast.Always always) {
                steps.add(new TaskForecast.Task(e.getKey(), TaskForecast.Status.RUN, always.why(), null));
            } else if (v != null) {
                steps.add(v);
            }
        }
        steps.addAll(moduleVerdicts);
        return new TaskForecast.Module(
                m.dir(),
                m.coord(),
                steps,
                m.sourceCount(),
                m.testCount(),
                m.producesJar(),
                m.producesImage(),
                m.reason());
    }

    /** Verdicts about the module rather than one of its steps. */
    private static boolean isModuleVerdict(String name) {
        return TaskNames.RESTORE_OUTPUTS.equals(name) || TaskNames.ORDER_CHECK.equals(name);
    }

    /** The plan's name for a verdict: javac's compile is keyed {@code compile-main} and planned {@code compile-java}. */
    private static String stepName(String verdict) {
        return TaskNames.COMPILE_MAIN.equals(verdict) ? TaskNames.COMPILE_JAVA : verdict;
    }
}
