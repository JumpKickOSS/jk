// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.HeapPlan;
import cc.jumpkick.engine.plugin.JvmOptions;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.Calibration;
import cc.jumpkick.runtime.base.HostLearnedRates;
import cc.jumpkick.runtime.base.StepTimings;
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
            BuildService.EtaModel etaModel,
            long etaMs,
            boolean parallelTests) {}

    static Resources seed(WorkspacePreflightPhase.Context preflight, WorkspaceBuildListener listener) {
        WorkspaceRequest incoming = preflight.request();
        List<StepTimings.Sample> timingSamples = Collections.synchronizedList(new ArrayList<>());
        List<HostLearnedRates.HostSample> hostSamples = Collections.synchronizedList(new ArrayList<>());

        Calibration.ensureAnnounced(incoming.jdksDir(), listener::onPreflight);

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

        ExplainPlan etaPlan = etaPlan(preflight, request, dirtyUnits);
        // The user's -w (0 = auto). The resolved share stays on `request` for the executor; the ETA
        // recomputes that share and, for auto, the class-wall worker count. Passing the share here
        // would look like an explicit pin.
        BuildService.EtaModel etaModel = BuildEta.estimateEtaModel(
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
        long etaMs = etaModel.etaMs();
        List<ModuleWorkCost> ordered = BuildEta.orderCostsLikeUnits(dirtyUnits, etaModel.costs());
        listener.onWorkModel(WorkModel.of(etaMs, etaModel.concurrency(), etaModel.serial(), parallelTests, ordered));
        listener.onEtaEstimate(etaMs);

        return new Resources(
                preflight,
                request,
                List.copyOf(dirtyUnits),
                List.copyOf(cleanUnits),
                timingSamples,
                hostSamples,
                etaModel,
                etaMs,
                parallelTests);
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
        } else {
            plan = BuildForecasting.explainFromGraph(
                    preflight.graph(), request.cache(), request.skipTests(), null, request.profile());
        }
        return BuildForecasting.restrictToSelection(plan, preflight.dirty());
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
