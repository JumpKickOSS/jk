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
import cc.jumpkick.runtime.KotlinAbiWarmup;
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
        long scheduleStart = Perf.start();
        long executeStartMs = clock.millis();
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
                            testClassesConsumed.contains(unit.dir()),
                            // A module with Kotlin consumers has its jar snapshotted for them before
                            // they are admitted, so their compile keys are memo lookups.
                            KotlinAbiWarmup.before(
                                    resources.preflight().graph(),
                                    unit,
                                    request.cache(),
                                    JkStores.storeCas(),
                                    artifactsReady),
                            clock),
                    (ready, results, _) ->
                            collect(request, prepared.plans(), workspaceLinks, ready, results, outcomes, observedRates),
                    request.maxModuleConcurrency(),
                    SessionCancel::cancelled,
                    // The root's after-build scripts read what the members produced, native
                    // tails included, so the root waits for the members to finish, not to publish.
                    unit -> unit.origin() == BuildGraph.Origin.ROOT);
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

    /** Return a failing outcome only when fail-fast policy should stop admission. */
    static @Nullable ModuleOutcome stoppingFailure(boolean keepGoing, ModuleOutcome outcome) {
        return !outcome.success() && !keepGoing ? outcome : null;
    }

    /** Run one module and emit its module start/finish lifecycle. */
    private static ModuleOutcome runModule(
            ModulePlan module,
            WorkspaceBuildListener listener,
            boolean testClassesConsumed,
            KotlinAbiWarmup.Warmup warmup,
            Clock clock) {
        BuildPlanListener moduleListener = listener.onModuleStart(module);
        if (moduleListener != null) module.plan().addListener(moduleListener);
        watchArtifactSteps(module.plan(), testClassesConsumed, warmup.artifactsReady());
        long started = clock.nanos();
        try {
            return runPlan(module, listener, clock, started);
        } finally {
            // Completion publishes the artifacts when the signal never fired; the consumers' memo
            // must be warm by then.
            warmup.await();
        }
    }

    private static ModuleOutcome runPlan(
            ModulePlan module, WorkspaceBuildListener listener, Clock clock, long started) {
        try {
            BuildPlanResult result = EffortWeights.withOverReserveTails(module.plan()::run);
            long millis = (clock.nanos() - started) / 1_000_000;
            boolean cancelled = result.userCancelled() || SessionCancel.cancelled();
            int exit = result.success() && !cancelled ? 0 : NativePlans.failureExitCode(module.plan(), result);
            boolean didWork = !result.success() || cancelled || BuildService.moduleDidWork(result);
            ModuleOutcome outcome = new ModuleOutcome(
                    module.coord(), module.dir(), result.success() && !cancelled, exit, millis, didWork, cancelled);
            ModuleOutcome.Image image = ImagePlans.outcomeOf(module.plan());
            if (image != null) outcome = outcome.withImage(image);
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
                String name = Dependency.workspaceName(ref);
                Path producer = name != null ? dirByName.get(name) : dirByCoord.get(ref);
                if (producer != null) consumed.add(producer);
            }
        }
        return consumed;
    }

    /**
     * The steps whose success publishes this module's artifacts to its dependents.
     *
     * <p>{@code package-jar} and {@code package-assembly} are the main artifacts a dependent
     * compiles against, and {@code compile-test-fixtures} is sibling-visible unconditionally.
     * {@code compile-test} joins the set only for a module whose test classes a sibling selects
     * with {@code kind = "tests"}. Gating on it anywhere else holds a downstream module's
     * {@code compile-java} behind an upstream test compile nothing can read.
     *
     * <p>The {@code isEmpty} arm keeps a module with no packaging steps publishing at its test
     * compile rather than falling through to the scheduler's publish-on-completion, which is behind
     * {@code run-tests} and so strictly later than what it does today.
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
        Set<String> artifactSteps = artifactWaitSet(plan, testClassesConsumed);
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
}
