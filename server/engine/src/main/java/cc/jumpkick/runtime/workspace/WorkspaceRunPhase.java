// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.SessionCancel;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.EffortWeights;
import cc.jumpkick.runtime.InstallPlans;
import cc.jumpkick.runtime.KotlinAbiWarmup;
import cc.jumpkick.runtime.base.Perf;
import cc.jumpkick.runtime.base.SiblingArtifacts;
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
import java.util.HashMap;
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

    /** The scheduled run on the system clock — what {@link WorkspaceExecute} calls. */
    static Run run(WorkspacePreparePhase.Prepared prepared, WorkspaceBuildListener listener) {
        return run(prepared, listener, Clock.SYSTEM);
    }

    /**
     * The run, with the clock its wall figures are read from. Every duration this phase reports —
     * the execute wall and each module's own millis — comes from {@code clock}, so a test can settle
     * them instead of sleeping to produce one.
     */
    static Run run(WorkspacePreparePhase.Prepared prepared, WorkspaceBuildListener listener, Clock clock) {
        WorkspaceResourcePhase.Resources resources = prepared.resources();
        WorkspaceRequest request = resources.request();
        Map<Path, Path> workspaceLinks =
                WorkspaceArtifacts.computeLinks(resources.preflight().moduleDirs(), request.entryDir());
        for (BuildGraph.BuildUnit unit : resources.cleanUnits()) {
            WorkspaceArtifacts.linkModule(request.entryDir(), unit.dir(), workspaceLinks);
        }

        // Whose test compilation any sibling can actually read. Computed once, off the manifests
        // the graph already parsed, because the edge map carries dirs and not dependency kinds.
        Set<Path> testClassesConsumed =
                testClassesConsumed(resources.preflight().graph().topoOrder());

        List<ModuleOutcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        List<Double> observedRates = Collections.synchronizedList(new ArrayList<>());
        SiblingArtifacts siblings = prepared.siblings();
        long scheduleStart = Perf.start();
        long executeStartMs = clock.millis();
        ModuleOutcome failure = null;
        if (!resources.dirtyUnits().isEmpty()) {
            failure = WorkspaceScheduler.run(
                    resources.dirtyUnits(),
                    BuildGraph.BuildUnit::dir,
                    // The transitive closure, so a dependent is admitted only once every module on
                    // its compile classpath — not only the ones it names — has published.
                    siblings.edges(),
                    (unit, publish) -> runModule(
                            Objects.requireNonNull(
                                    prepared.plans().get(unit.dir()),
                                    () -> "admitted a unit with no prepared plan: " + unit.dir()),
                            listener,
                            testClassesConsumed.contains(unit.dir()),
                            unit.manifest().relocates(),
                            // A module with Kotlin consumers has its classes tree snapshotted for
                            // them before they are admitted, so their compile keys are memo lookups.
                            KotlinAbiWarmup.before(
                                    resources.preflight().graph(), unit, request.cache(), JkStores.storeCas(), publish),
                            siblings,
                            clock),
                    (ready, results, _) ->
                            collect(request, prepared.plans(), workspaceLinks, ready, results, outcomes, observedRates),
                    request.maxModuleConcurrency(),
                    SessionCancel::cancelled,
                    // The root's after-build scripts read what the members produced, native
                    // tails included, so the root waits for the members to finish, not to publish.
                    unit -> unit.origin() == BuildGraph.Origin.ROOT,
                    // Fail-fast stops the siblings still building and waits for them, so every
                    // module that started is in the record, the stopped ones as stopped.
                    unit -> {
                        ModulePlan sibling = prepared.plans().get(unit.dir());
                        if (sibling != null) sibling.plan().stop();
                    });
        }
        Perf.end("ws-schedule-run", scheduleStart);
        long executeWallMs = Math.max(0L, clock.millis() - executeStartMs);
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
            WorkspaceArtifacts.linkModule(request.entryDir(), ready.get(i).dir(), workspaceLinks);
            ModulePlan plan = plans.get(ready.get(i).dir());
            if (plan != null && !plan.fullyCached() && plan.weight() > 0 && outcome.millis() > 0) {
                observedRates.add(outcome.millis() / (double) plan.weight());
            }
        }
        return null;
    }

    /**
     * The step a failed plan stopped at: the first diagnosed error's step, else the first step
     * reported failed. Empty when the plan failed without either — nothing to name.
     */
    static Optional<String> failedStep(BuildPlanResult result) {
        for (BuildPlanResult.Diagnostic error : result.errors()) {
            if (!error.step().isBlank()) return Optional.of(error.step());
        }
        for (BuildPlanResult.StepReport step : result.steps()) {
            if (step.status() == TaskStatus.FAIL) return Optional.of(step.name());
        }
        return Optional.empty();
    }

    /** Return a failing outcome only when fail-fast policy should stop admission. */
    static @Nullable ModuleOutcome stoppingFailure(boolean keepGoing, ModuleOutcome outcome) {
        return !outcome.success() && !keepGoing ? outcome : null;
    }

    /**
     * Run one module and emit its module start/finish lifecycle. Two signals leave the plan: the
     * classes publish admits dependents to the schedule (they compile against this module's
     * classes tree), and the artifact publish releases the dependents' package and test steps
     * (they read its jar). Completion fires both, so a module that failed early wedges nothing.
     */
    private static ModuleOutcome runModule(
            ModulePlan module,
            WorkspaceBuildListener listener,
            boolean testClassesConsumed,
            boolean relocates,
            KotlinAbiWarmup.Warmup warmup,
            SiblingArtifacts siblings,
            Clock clock) {
        BuildPlanListener moduleListener = listener.onModuleStart(module);
        if (moduleListener != null) module.plan().addListener(moduleListener);
        watchClassesSteps(module.plan(), relocates, warmup.publish());
        watchArtifactSteps(module.plan(), testClassesConsumed, () -> siblings.published(module.dir()));
        long started = clock.nanos();
        try {
            return runPlan(module, listener, siblings, clock, started);
        } finally {
            siblings.completed(module.dir());
            // Completion admits the dependents when the signal never fired; the consumers' memo
            // must be warm by then.
            warmup.await();
        }
    }

    private static ModuleOutcome runPlan(
            ModulePlan module, WorkspaceBuildListener listener, SiblingArtifacts siblings, Clock clock, long started) {
        try {
            BuildPlanResult result = EffortWeights.withOverReserveTails(module.plan()::run);
            // Recorded before completion publishes: a dependent that finds this module's output
            // absent then names the step that failed to produce it.
            if (!result.success()) failedStep(result).ifPresent(step -> siblings.failed(module.coord(), step));
            long millis = (clock.nanos() - started) / 1_000_000;
            boolean cancelled = result.userCancelled() || SessionCancel.cancelled();
            int exit = result.success() && !cancelled ? 0 : NativePlans.failureExitCode(module.plan(), result);
            boolean didWork = !result.success() || cancelled || BuildService.moduleDidWork(result);
            ModuleOutcome outcome = new ModuleOutcome(
                    module.coord(), module.dir(), result.success() && !cancelled, exit, millis, didWork, cancelled);
            ModuleOutcome.Image image = ImagePlans.outcomeOf(module.plan());
            if (image != null) outcome = outcome.withImage(image);
            ModuleOutcome.Shelved shelved = InstallPlans.shelvedOf(module.plan());
            if (shelved != null) outcome = outcome.withShelved(shelved);
            listener.onModuleFinish(outcome);
            return outcome;
        } catch (RuntimeException e) {
            long millis = (clock.nanos() - started) / 1_000_000;
            boolean cancelled = SessionCancel.cancelled();
            ModuleOutcome outcome = new ModuleOutcome(module.coord(), module.dir(), false, 1, millis, true, cancelled);
            listener.onModuleFinish(outcome);
            return outcome;
        }
    }

    /**
     * Module dirs whose {@code classes/test} some sibling consumes, and whose artifact publish
     * therefore still has to wait for {@code compile-test}.
     *
     * <p>A {@code kind = "tests"} edge is the only thing that puts one module's test output on
     * another's classpath, so for every other module the test compile is private and holding the
     * publish for it only delays the modules downstream of it. In a workspace with no tests-kind
     * edge anywhere — jk's own, and most — that is every module.
     *
     * <p>Refs resolve the way {@link WorkspaceClasspath} resolves them, against the same manifests:
     * a {@code workspace:<name>} placeholder by module name, anything else by coord. A ref matching
     * no unit is an external Maven test-jar or a broken edge, and neither selects a sibling's
     * classes/test — the same conclusion WorkspaceClasspath reaches when its sibling index misses.
     */
    static Set<Path> testClassesConsumed(List<BuildGraph.BuildUnit> units) {
        Map<String, Path> dirByCoord = new HashMap<>();
        Map<String, Path> dirByName = new HashMap<>();
        for (BuildGraph.BuildUnit unit : units) {
            dirByCoord.put(unit.coord(), unit.dir());
            dirByName.put(unit.manifest().project().name(), unit.dir());
        }
        Set<Path> consumed = new HashSet<>();
        for (BuildGraph.BuildUnit unit : units) {
            for (String ref : WorkspaceClasspath.directTestsKindRefs(unit.manifest())) {
                String qualified = Dependency.workspaceCoordinate(ref);
                String name = Dependency.workspaceName(ref);
                Path producer = qualified != null
                        ? dirByCoord.get(qualified)
                        : name != null ? dirByName.get(name) : dirByCoord.get(ref);
                if (producer != null) consumed.add(producer);
            }
        }
        return consumed;
    }

    /**
     * The steps whose success makes what this module's dependents compile against whole: every
     * language compile in the plan, the classes assembler of a mixed module, and the resource
     * copy — resources are not compiled against, but they land in the same tree, and a dependent
     * that fingerprints the tree while they are being written would memoize a token under an
     * identity no later build will see. A module that {@code relocates} packages is compiled
     * against through its {@code -all.jar}, so its {@code package-assembly} joins the set. A plan
     * with none of these (a sourceless root) publishes on completion.
     */
    static Set<String> classesWaitSet(BuildPlan plan, boolean relocates) {
        Set<String> present = new HashSet<>();
        for (Task step : plan.steps()) present.add(step.name());
        Set<String> wait = new HashSet<>();
        for (String step : List.of(
                TaskNames.COMPILE_JAVA,
                TaskNames.COMPILE_KOTLIN,
                TaskNames.COMPILE_GROOVY,
                TaskNames.ASSEMBLE_CLASSES,
                TaskNames.COPY_RESOURCES)) {
            if (present.contains(step)) wait.add(step);
        }
        if (relocates && present.contains(TaskNames.PACKAGE_ASSEMBLY)) wait.add(TaskNames.PACKAGE_ASSEMBLY);
        return wait;
    }

    /** Admit dependents once every step that writes what they compile against is terminal-successful. */
    static void watchClassesSteps(BuildPlan plan, boolean relocates, Runnable publish) {
        watchSteps(plan, classesWaitSet(plan, relocates), publish);
    }

    /**
     * The steps whose success publishes this module's artifacts to its dependents' package and
     * test steps.
     *
     * <p>{@code package-jar} and {@code package-assembly} are the main artifacts a dependent runs
     * against, and {@code compile-test-fixtures} is sibling-visible unconditionally.
     * {@code compile-test} joins the set only for a module whose test classes a sibling selects
     * with {@code kind = "tests"}. Gating on it anywhere else holds a downstream module's test
     * compile behind an upstream test compile nothing can read.
     *
     * <p>The {@code isEmpty} arm keeps a module with no packaging steps publishing at its test
     * compile rather than falling through to publish-on-completion, which is behind
     * {@code run-tests} and so strictly later.
     */
    static Set<String> artifactWaitSet(BuildPlan plan, boolean testClassesConsumed) {
        Set<String> present = new HashSet<>();
        for (Task step : plan.steps()) present.add(step.name());
        Set<String> wait = new HashSet<>();
        for (String main :
                List.of(TaskNames.PACKAGE_JAR, TaskNames.PACKAGE_ASSEMBLY, TaskNames.COMPILE_TEST_FIXTURES)) {
            if (present.contains(main)) wait.add(main);
        }
        if (present.contains(TaskNames.COMPILE_TEST) && (testClassesConsumed || wait.isEmpty())) {
            wait.add(TaskNames.COMPILE_TEST);
        }
        return wait;
    }

    /** Publish artifacts once every cross-module artifact step is terminal-successful. */
    static void watchArtifactSteps(BuildPlan plan, boolean testClassesConsumed, Runnable artifactsReady) {
        watchSteps(plan, artifactWaitSet(plan, testClassesConsumed), artifactsReady);
    }

    /** Run {@code publish} once every step of {@code steps} is terminal-successful; never for an empty set. */
    private static void watchSteps(BuildPlan plan, Set<String> steps, Runnable publish) {
        if (steps.isEmpty()) return;
        AtomicInteger remaining = new AtomicInteger(steps.size());
        plan.addListener(new BuildPlanListener() {
            @Override
            public void stepFinish(
                    String step, @Nullable String group, TaskStatus status, Duration duration, Duration waited) {
                if (!steps.contains(step)) return;
                if (status != TaskStatus.SUCCESS && status != TaskStatus.SKIPPED) return;
                if (remaining.decrementAndGet() == 0) publish.run();
            }
        });
    }
}
