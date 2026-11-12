// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.HeapPlan;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.Calibration;
import cc.jumpkick.runtime.base.HostLearnedRates;
import cc.jumpkick.runtime.base.RunShapes;
import cc.jumpkick.runtime.base.StepTimings;
import cc.jumpkick.runtime.base.SuiteSelection;
import cc.jumpkick.test.TestWorkers;
import cc.jumpkick.wire.runtime.ExplainPlan;
import cc.jumpkick.wire.runtime.ModuleWorkCost;
import cc.jumpkick.wire.runtime.WorkModel;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Seeds host resources and the sole ETA/work model before module preparation. */
@NullMarked
final class WorkspaceResourcePhase {

    private WorkspaceResourcePhase() {}

    /** Typed state handed from resource planning to module preparation. */
    record Resources(
            WorkspacePreflightPhase.Context preflight,
            WorkspaceRequest request,
            List<BuildGraph.BuildUnit> dirtyUnits,
            List<BuildGraph.BuildUnit> cleanUnits,
            List<StepTimings.Sample> timingSamples,
            List<HostLearnedRates.HostSample> hostSamples,
            BackgroundEta eta,
            boolean parallelTests,
            ProvisionalWatch provisional) {

        /**
         * {@code dir}'s classes are published: when a module the estimate priced on a guess reads
         * them, its verdicts are lookups now, and the estimate is re-priced (JK-0PDBN).
         */
        void classesPublished(Path dir) {
            if (provisional.waitsOn(dir)) eta.reprice();
        }
    }

    /**
     * The modules the latest pricing guessed at, each with the siblings it reads: their verdicts
     * ({@link TaskForecast.Task#provisional}) wait on a sibling this build has yet to compile.
     */
    static final class ProvisionalWatch {
        private volatile Map<Path, Set<Path>> waiting = Map.of();

        /** The guesses of a pricing's {@code plan} replace the last one's. */
        void priced(ExplainPlan plan) {
            Map<Path, Set<Path>> next = new HashMap<>();
            for (TaskForecast.Module m : plan.modules()) {
                if (m.steps().stream().anyMatch(TaskForecast.Task::provisional)) {
                    next.put(m.dir(), plan.edges().getOrDefault(m.dir(), Set.of()));
                }
            }
            waiting = Map.copyOf(next);
        }

        /** Whether a module the latest pricing guessed at reads {@code dir}. */
        boolean waitsOn(Path dir) {
            for (Set<Path> reads : waiting.values()) {
                if (reads.contains(dir)) return true;
            }
            return false;
        }
    }

    static Resources seed(WorkspacePreflightPhase.Context preflight, WorkspaceBuildListener listener) {
        WorkspaceRequest incoming = preflight.request();
        List<StepTimings.Sample> timingSamples = Collections.synchronizedList(new ArrayList<>());
        List<HostLearnedRates.HostSample> hostSamples = Collections.synchronizedList(new ArrayList<>());

        Calibration.ensureAnnounced(incoming.jdksDir(), listener::onPreflight);
        Long requestId = JobWorkers.currentRequestId();
        RunShapes.mark(requestId, shape(incoming));

        List<BuildGraph.BuildUnit> dirtyUnits = new ArrayList<>();
        List<BuildGraph.BuildUnit> cleanUnits = new ArrayList<>();
        for (BuildGraph.BuildUnit unit : preflight.units()) {
            if (preflight.dirty().contains(unit.dir())) dirtyUnits.add(unit);
            else cleanUnits.add(unit);
        }

        int processorCap = Runtime.getRuntime().availableProcessors();
        boolean parallelTests = SessionContext.current().parallelTests();
        int width = BuildGraph.maxReadyWidth(dirtyUnits, preflight.graph().edges());
        if (incoming.maxModuleConcurrency() > 0) {
            width = Math.min(width, incoming.maxModuleConcurrency());
        }
        int jobsBudget = TestWorkers.jobsBudget(incoming.maxModuleConcurrency());
        WorkspaceRequest request = incoming.withWorkers(resolveAutoWorkers(incoming, width, jobsBudget));
        if (request.applyMemoryPlan()) {
            JvmOptions.planAndApply(HeapPlan.requestedJvms(width, request.workers(), parallelTests, processorCap));
        }

        // The ETA walks the forecast of every scheduled module (seconds on a large workspace) and
        // nothing scheduling needs comes from it, so it is priced beside the build.
        WorkspaceRequest priced = request;
        ProvisionalWatch provisional = new ProvisionalWatch();
        BackgroundEta eta = BackgroundEta.deferred(
                        () -> {
                            ExplainPlan plan = etaPlan(preflight, priced, dirtyUnits);
                            provisional.priced(plan);
                            return estimate(plan, priced, incoming, parallelTests, null);
                        },
                        model -> {
                            RunShapes.markPriced(requestId, model.frontier(), model.curves());
                            listener.onWorkModel(workModel(model, dirtyUnits, parallelTests));
                            listener.onEtaEstimate(model.etaMs());
                        })
                // A module the estimate guessed at has compiled: what its dependents run is read off
                // its outputs now, every scheduled module's forecast walked again.
                .repricing(
                        seedModel -> {
                            ExplainPlan plan = scheduledPlan(preflight, priced);
                            provisional.priced(plan);
                            return estimate(plan, priced, incoming, parallelTests, seedModel);
                        },
                        (model, n) -> listener.onWorkModel(
                                workModel(model, dirtyUnits, parallelTests).asRevision(n)));

        return new Resources(
                preflight,
                request,
                List.copyOf(dirtyUnits),
                List.copyOf(cleanUnits),
                timingSamples,
                hostSamples,
                eta,
                parallelTests,
                provisional);
    }

    private static WorkModel workModel(
            BuildService.EtaModel model, List<BuildGraph.BuildUnit> dirtyUnits, boolean parallelTests) {
        List<ModuleWorkCost> ordered = BuildEta.orderCostsLikeUnits(dirtyUnits, model.costs());
        return new WorkModel(model.etaMs(), model.concurrency(), model.serial(), parallelTests, ordered, model.steps());
    }

    /**
     * What shapes this build's step walls, for the step ledger its run is recorded into: the test
     * profile, a forced or rebuild run, and a class filter that narrows the suites.
     */
    static RunShapes.Shape shape(WorkspaceRequest request) {
        var session = SessionContext.current();
        boolean forced = session.config().forceOr(false) || session.config().rebuildOr(false);
        boolean filtered = !session.testSelection().classes().isEmpty();
        return new RunShapes.Shape(
                request.profile() == null ? "" : request.profile(), forced, filtered, SuiteSelection.current());
    }

    /**
     * The work model of this build: the forecast of the scheduled modules priced against step
     * history. The user's -w (0 = auto) is passed rather than the resolved share on {@code request}:
     * the ETA recomputes that share, and passing it would look like an explicit pin.
     */
    static BuildService.EtaModel estimate(
            ExplainPlan etaPlan,
            WorkspaceRequest request,
            WorkspaceRequest incoming,
            boolean parallelTests,
            BuildService.@Nullable EtaModel seed) {
        return BuildEta.estimateEtaModel(
                etaPlan,
                request.entryDir(),
                request.cache(),
                incoming.workers(),
                request.jdksDir(),
                request.profile(),
                request.skipTests(),
                request.verbose(),
                parallelTests,
                request.maxModuleConcurrency(),
                seed);
    }

    /**
     * Every scheduled module's forecast as the disk stands now, for a re-pricing: the modules that
     * have compiled since the seed answer from their outputs.
     */
    private static ExplainPlan scheduledPlan(WorkspacePreflightPhase.Context preflight, WorkspaceRequest request) {
        ExplainPlan plan = BuildForecasting.scheduledPlan(
                preflight.graph(), request.cache(), request.skipTests(), request.profile(), preflight.dirty());
        return testRunPlan(BuildForecasting.restrictToSelection(plan, preflight.dirty()), preflight, request);
    }

    private static ExplainPlan testRunPlan(
            ExplainPlan selected, WorkspacePreflightPhase.Context preflight, WorkspaceRequest request) {
        return request.testOnly()
                ? withoutPackagingTails(selected, WorkspacePreflightPhase.launchedAssemblies(preflight.graph()))
                : selected;
    }

    private static ExplainPlan etaPlan(
            WorkspacePreflightPhase.Context preflight,
            WorkspaceRequest request,
            List<BuildGraph.BuildUnit> dirtyUnits) {
        ExplainPlan plan;
        boolean distrust = SessionContext.current().config().forceOr(false)
                || SessionContext.current().config().rebuildOr(false);
        if (preflight.forecast().isPresent()
                && !preflight.forecast().orElseThrow().modules().isEmpty()) {
            BuildForecasting.Preflight forecast = preflight.forecast().orElseThrow();
            plan = new ExplainPlan(
                    forecast.modules(),
                    preflight.graph().edges(),
                    preflight.graph().maxReadyWidth(),
                    List.of());
        } else if (dirtyUnits.isEmpty() && !distrust) {
            plan = BuildForecasting.fullyCachedExplainPlan(preflight.graph());
        } else if (preflight.forecast().isPresent() && !distrust) {
            // A memo hit: the preflight walked nothing, and only the dirty set needs pricing.
            plan = BuildForecasting.scheduledPlan(
                    preflight.graph(), request.cache(), request.skipTests(), request.profile(), preflight.dirty());
        } else {
            plan = BuildForecasting.explainFromGraph(
                    preflight.graph(), request.cache(), request.skipTests(), null, request.profile());
        }
        return testRunPlan(BuildForecasting.restrictToSelection(plan, preflight.dirty()), preflight, request);
    }

    /**
     * {@code plan} without the packaging tails (assembly, native image, sources jar): a test run
     * packages past the jar only for the fat jar a sibling's suite launches ({@code launched},
     * {@code PlannerTails.joinTests}). A forced test run's forecast listed them all as runs, and the
     * estimate priced jk's 50 s native image into every forced integration run; dropping the
     * launched fat jar too left the engine's 1.3 s assembly out of every {@code jk test}.
     */
    static ExplainPlan withoutPackagingTails(ExplainPlan plan, Set<Path> launched) {
        List<TaskForecast.Module> modules = new ArrayList<>();
        for (TaskForecast.Module m : plan.modules()) {
            boolean fatJarLaunched = launched.contains(BuildGraph.canonicalPath(m.dir()));
            List<TaskForecast.Task> steps = m.steps().stream()
                    .filter(t -> !TaskNames.PACKAGING_TAILS.contains(t.name())
                            || (fatJarLaunched && TaskNames.PACKAGE_ASSEMBLY.equals(t.name())))
                    .toList();
            modules.add(new TaskForecast.Module(
                    m.dir(), m.coord(), steps, m.sourceCount(), m.testCount(), m.producesJar(), false, m.reason()));
        }
        return new ExplainPlan(modules, plan.edges(), plan.maxReadyWidth(), plan.errors());
    }

    /**
     * An explicit {@code -w N} is never rewritten; auto is the jobs budget shared across the
     * modules that can run at once ({@link TestWorkers#autoShare}), the same call the ETA makes.
     */
    static int resolveAutoWorkers(WorkspaceRequest request, int width, int jobsBudget) {
        if (request.workers() > 0) return request.workers();
        return TestWorkers.autoShare(jobsBudget, width);
    }
}
