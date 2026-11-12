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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.NullMarked;

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
            boolean parallelTests) {}

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
        BackgroundEta eta = BackgroundEta.deferred(
                () -> estimate(preflight, priced, incoming, dirtyUnits, parallelTests), model -> {
                    RunShapes.markPriced(requestId, model.frontier(), model.curves());
                    List<ModuleWorkCost> ordered = BuildEta.orderCostsLikeUnits(dirtyUnits, model.costs());
                    listener.onWorkModel(new WorkModel(
                            model.etaMs(), model.concurrency(), model.serial(), parallelTests, ordered, model.steps()));
                    listener.onEtaEstimate(model.etaMs());
                });

        return new Resources(
                preflight,
                request,
                List.copyOf(dirtyUnits),
                List.copyOf(cleanUnits),
                timingSamples,
                hostSamples,
                eta,
                parallelTests);
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
            WorkspacePreflightPhase.Context preflight,
            WorkspaceRequest request,
            WorkspaceRequest incoming,
            List<BuildGraph.BuildUnit> dirtyUnits,
            boolean parallelTests) {
        ExplainPlan etaPlan = etaPlan(preflight, request, dirtyUnits);
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
                request.maxModuleConcurrency());
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
        ExplainPlan selected = BuildForecasting.restrictToSelection(plan, preflight.dirty());
        return request.testOnly() ? withoutPackagingTails(selected) : selected;
    }

    /**
     * {@code plan} without the packaging tails (assembly, native image, sources jar): a test run
     * never packages past the jar. A forced test run's forecast listed them as runs, and the
     * estimate priced jk's 50 s native image into every forced integration run.
     */
    static ExplainPlan withoutPackagingTails(ExplainPlan plan) {
        List<TaskForecast.Module> modules = new ArrayList<>();
        for (TaskForecast.Module m : plan.modules()) {
            List<TaskForecast.Task> steps = m.steps().stream()
                    .filter(t -> !TaskNames.PACKAGING_TAILS.contains(t.name()))
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
