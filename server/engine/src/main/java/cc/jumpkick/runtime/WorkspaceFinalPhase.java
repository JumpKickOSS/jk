// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NullMarked;

/** Aggregates execution precedence and learns only from complete successful runs. */
@NullMarked
final class WorkspaceFinalPhase {

    private WorkspaceFinalPhase() {}

    record Decision(WorkspaceResult result, boolean learn) {}

    static WorkspaceResult complete(WorkspaceRunPhase.Run run) {
        Decision decision = aggregate(run.outcomes(), run.scheduleFailure(), run.cancelled());
        if (decision.learn()) learn(run);
        return decision.result();
    }

    /**
     * Cancellation wins with exit 1; otherwise the scheduler's first failure, then the first
     * collected failure, determines the result.
     */
    static Decision aggregate(
            List<ModuleOutcome> outcomes, Optional<ModuleOutcome> scheduleFailure, boolean cancelled) {
        ModuleOutcome firstFailure = scheduleFailure.orElse(null);
        if (firstFailure == null) {
            for (ModuleOutcome outcome : outcomes) {
                if (!outcome.success()) {
                    firstFailure = outcome;
                    break;
                }
            }
        }
        boolean success = firstFailure == null && !cancelled;
        int exit = success ? 0 : cancelled ? 1 : firstFailure.exitCode();
        WorkspaceResult result = new WorkspaceResult(success, exit, List.copyOf(outcomes), List.of(), cancelled);
        return new Decision(result, success);
    }

    private static void learn(WorkspaceRunPhase.Run run) {
        WorkspaceResourcePhase.Resources resources = run.prepared().resources();
        WorkspaceRequest request = resources.request();
        BuildGraph.Result graph = resources.preflight().graph();
        long now = System.currentTimeMillis();

        BuildEta.logSeedQuality(
                resources.etaMs(), run.executeWallMs(), resources.dirtyUnits().size());
        ScheduleBias.observe(
                request.entryDir(),
                resources.etaModel().rawScheduleMs(),
                run.executeWallMs(),
                resources.dirtyUnits().size());
        StepTimings.record(request.cache(), resources.timingSamples(), StepTimings.DEFAULT_ALPHA, now);
        Calibration.learnFromSuccess(List.copyOf(resources.hostSamples()));
        medianRate(run.observedRates()).ifPresent(rate -> Calibration.refine(rate, now));

        if (shouldStoreCleanMemo(request)) {
            Map<Path, String> fingerprints = PreflightMemo.snapshotFingerprints(graph, request.skipTests());
            if (!fingerprints.isEmpty()) {
                PreflightMemo.storeDirty(request.entryDir(), graph, request.skipTests(), Set.of(), fingerprints);
            }
        }
    }

    /** Only a complete, unhinted PACKAGE build can certify the whole graph clean. */
    static boolean shouldStoreCleanMemo(WorkspaceRequest request) {
        return request.dirtyHint() == null && !request.testOnly() && request.target() == WorkspaceTarget.PACKAGE;
    }

    private static Optional<Double> medianRate(List<Double> rates) {
        if (rates.isEmpty()) return Optional.empty();
        List<Double> sorted = new ArrayList<>(rates);
        sorted.sort(Double::compareTo);
        int size = sorted.size();
        double median = size % 2 == 1 ? sorted.get(size / 2) : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
        return Optional.of(median);
    }
}
