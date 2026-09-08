// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.SessionCancel;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.EffortWeights;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.runtime.base.WorkspaceArtifacts;
import cc.jumpkick.runtime.base.WorkspaceScheduler;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Runs prepared plans through the workspace scheduler and records completed work. */
@NullMarked
final class WorkspaceRunPhase {

    private WorkspaceRunPhase() {}

    /** Typed execution observations handed to final aggregation and learning. */
    record Run(
            WorkspacePreparePhase.Prepared prepared,
            List<ModuleOutcome> outcomes,
            Optional<ModuleOutcome> scheduleFailure,
            List<Double> observedRates,
            long executeWallMs,
            boolean cancelled) {}

    static Run run(WorkspacePreparePhase.Prepared prepared, WorkspaceBuildListener listener) {
        WorkspaceResourcePhase.Resources resources = prepared.resources();
        WorkspaceRequest request = resources.request();
        Map<Path, Path> workspaceLinks =
                WorkspaceArtifacts.computeLinks(resources.preflight().moduleDirs(), request.entryDir());
        for (BuildGraph.BuildUnit unit : resources.cleanUnits()) {
            WorkspaceArtifacts.linkModule(unit.dir(), workspaceLinks);
        }

        List<ModuleOutcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        List<Double> observedRates = Collections.synchronizedList(new ArrayList<>());
        long scheduleStart = Perf.start();
        long executeStartMs = System.currentTimeMillis();
        ModuleOutcome failure = null;
        if (!resources.dirtyUnits().isEmpty()) {
            failure = WorkspaceScheduler.run(
                    resources.dirtyUnits(),
                    BuildGraph.BuildUnit::dir,
                    resources.preflight().graph().edges(),
                    (unit, artifactsReady) -> runModule(
                            Objects.requireNonNull(
                                    prepared.plans().get(unit.dir()),
                                    () -> "admitted a unit with no prepared plan: " + unit.dir()),
                            listener,
                            artifactsReady),
                    (ready, results, _) ->
                            collect(request, prepared.plans(), workspaceLinks, ready, results, outcomes, observedRates),
                    request.maxModuleConcurrency(),
                    SessionCancel::cancelled);
        }
        Perf.end("ws-schedule-run", scheduleStart);
        long executeWallMs = Math.max(0L, System.currentTimeMillis() - executeStartMs);
        return new Run(
                prepared,
                List.copyOf(outcomes),
                Optional.ofNullable(failure),
                List.copyOf(observedRates),
                executeWallMs,
                SessionCancel.cancelled());
    }

    private static @Nullable ModuleOutcome collect(
            WorkspaceRequest request,
            Map<Path, ModulePlan> plans,
            Map<Path, Path> workspaceLinks,
            List<BuildGraph.BuildUnit> ready,
            List<ModuleOutcome> results,
            List<ModuleOutcome> outcomes,
            List<Double> observedRates) {
        for (int i = 0; i < results.size(); i++) {
            ModuleOutcome outcome = results.get(i);
            outcomes.add(outcome);
            ModuleOutcome stop = stoppingFailure(request.keepGoing(), outcome);
            if (stop != null) return stop;
            WorkspaceArtifacts.linkModule(ready.get(i).dir(), workspaceLinks);
            ModulePlan plan = plans.get(ready.get(i).dir());
            if (plan != null && !plan.fullyCached() && plan.weight() > 0 && outcome.millis() > 0) {
                observedRates.add(outcome.millis() / (double) plan.weight());
            }
        }
        return null;
    }

    /** Return a failing outcome only when fail-fast policy should stop admission. */
    static @Nullable ModuleOutcome stoppingFailure(boolean keepGoing, ModuleOutcome outcome) {
        return !outcome.success() && !keepGoing ? outcome : null;
    }

    /** Run one module and emit its module start/finish lifecycle. */
    private static ModuleOutcome runModule(
            ModulePlan module, WorkspaceBuildListener listener, Runnable artifactsReady) {
        BuildPlanListener moduleListener = listener.onModuleStart(module);
        if (moduleListener != null) module.plan().addListener(moduleListener);
        watchArtifactSteps(module.plan(), artifactsReady);
        long started = System.nanoTime();
        try {
            BuildPlanResult result = EffortWeights.withOverReserveTails(module.plan()::run);
            long millis = (System.nanoTime() - started) / 1_000_000;
            boolean cancelled = result.userCancelled() || SessionCancel.cancelled();
            int exit = result.success() && !cancelled ? 0 : NativePlans.failureExitCode(module.plan(), result);
            boolean didWork = !result.success() || cancelled || BuildService.moduleDidWork(result);
            ModuleOutcome outcome = new ModuleOutcome(
                    module.coord(), module.dir(), result.success() && !cancelled, exit, millis, didWork, cancelled);
            ModuleOutcome.Image image = imageOutcomeOf(module.plan());
            if (image != null) outcome = outcome.withImage(image);
            listener.onModuleFinish(outcome);
            return outcome;
        } catch (RuntimeException e) {
            long millis = (System.nanoTime() - started) / 1_000_000;
            boolean cancelled = SessionCancel.cancelled();
            ModuleOutcome outcome = new ModuleOutcome(module.coord(), module.dir(), false, 1, millis, true, cancelled);
            listener.onModuleFinish(outcome);
            return outcome;
        }
    }

    /** Publish artifacts once every cross-module artifact step is terminal-successful. */
    static void watchArtifactSteps(BuildPlan plan, Runnable artifactsReady) {
        Set<String> artifactSteps = new HashSet<>();
        for (Task step : plan.steps()) {
            if (TaskNames.PACKAGE_JAR.equals(step.name())
                    || TaskNames.PACKAGE_ASSEMBLY.equals(step.name())
                    || TaskNames.COMPILE_TEST.equals(step.name())
                    || TaskNames.COMPILE_TEST_FIXTURES.equals(step.name())) {
                artifactSteps.add(step.name());
            }
        }
        if (artifactSteps.isEmpty()) return;
        AtomicInteger remaining = new AtomicInteger(artifactSteps.size());
        plan.addListener(new BuildPlanListener() {
            @Override
            public void stepFinish(
                    String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                if (!artifactSteps.contains(step)) return;
                if (status != TaskStatus.SUCCESS && status != TaskStatus.SKIPPED) return;
                if (remaining.decrementAndGet() == 0) artifactsReady.run();
            }
        });
    }

    private static ModuleOutcome.@Nullable Image imageOutcomeOf(BuildPlan plan) {
        var config = plan.get(ImagePlans.CONFIG).orElse(null);
        Path tarball = plan.get(ImagePlans.TARBALL_PATH).orElse(null);
        String reference = plan.get(ImagePlans.IMAGE_REF).orElse(null);
        if (config == null && tarball == null && reference == null) return null;
        var project = plan.get(BuildPlanner.PROJECT).orElse(null);
        boolean daemonMode = tarball == null
                && (config == null
                        || config.registry() == null
                        || config.registry().isBlank());
        String daemon = !daemonMode
                ? null
                : config != null && config.dockerExecutable() != null ? config.dockerExecutable() : "docker";
        return new ModuleOutcome.Image(
                reference,
                tarball != null ? tarball.toString() : null,
                project != null ? project.project().name() : null,
                project != null ? project.project().version() : null,
                daemon);
    }
}
