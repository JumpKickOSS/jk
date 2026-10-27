// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.Calibration;
import cc.jumpkick.runtime.ModuleInputProvenance;
import cc.jumpkick.runtime.PreflightMemo;
import cc.jumpkick.runtime.SettledReads;
import cc.jumpkick.runtime.TestClassMatch;
import cc.jumpkick.runtime.base.ScheduleBias;
import cc.jumpkick.runtime.base.StepTimings;
import cc.jumpkick.task.VerifyCache;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceRequest;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Aggregates execution precedence and learns only from complete successful runs. */
@NullMarked
final class WorkspaceFinalPhase {

    private WorkspaceFinalPhase() {}

    record Decision(WorkspaceResult result, boolean learn) {}

    static WorkspaceResult complete(WorkspaceRunPhase.Run run) {
        Decision decision = aggregate(run.outcomes(), run.scheduleFailure(), run.cancelled());
        if (!decision.learn()) return decision.result();
        WorkspaceResult noMatch = noClassMatched(run);
        if (noMatch != null) return noMatch;
        WorkspaceResult diverged = cacheDivergence(run);
        if (diverged != null) return diverged;
        learn(run);
        return decision.result();
    }

    /**
     * The one run-wide failure a green run can still turn into: every module skipped the
     * {@code --class} patterns. Each module only knows its own suite; this is where their answers
     * meet. {@code null} when some module matched, or the run selected no classes.
     */
    static @Nullable WorkspaceResult noClassMatched(WorkspaceRunPhase.Run run) {
        List<BuildPlan> plans =
                run.prepared().plans().values().stream().map(ModulePlan::plan).toList();
        boolean skipTests = run.prepared().resources().request().skipTests();
        String verdict = TestClassMatch.runWideVerdict(SessionContext.current(), skipTests, plans);
        if (verdict == null) return null;
        return new WorkspaceResult(false, Exit.USAGE, run.outcomes(), List.of(verdict), false);
    }

    /**
     * A verifying build's verdict on the preflight: every output of a module it would have skipped
     * that the build produced otherwise. {@code null} when there is none, or the build is not
     * verifying.
     */
    static @Nullable WorkspaceResult cacheDivergence(WorkspaceRunPhase.Run run) {
        if (!VerifyCache.on()) return null;
        List<String> found;
        try {
            found = VerifyCache.divergences(run.prepared()
                    .resources()
                    .preflight()
                    .forecast()
                    .map(BuildForecasting.Preflight::claims)
                    .orElse(Map.of()));
        } catch (IOException unreadable) {
            found = List.of("--verify-cache: the outputs could not be read back: " + unreadable.getMessage());
        }
        if (found.isEmpty()) return null;
        return new WorkspaceResult(false, Exit.FAILURE, run.outcomes(), found, false);
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
        // Cancel precedes failure: a cancelled run exits 1 even when a module also failed.
        // Reaching the third arm means neither succeeded nor cancelled, which is only
        // possible with a failure in hand.
        int exit = success
                ? 0
                : cancelled ? 1 : Objects.requireNonNull(firstFailure).exitCode();
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
            certifyClean(
                    request.entryDir(),
                    graph,
                    request.skipTests(),
                    request.profile(),
                    resources.preflight().forecast());
        }
        // Any run that left a module's classes current vouches for them to the graph, a test run too:
        // an agent's loop is `run` (tests), and a graph that waited for a full build had no calls.
        Set<Path> built = new HashSet<>();
        for (ModuleOutcome o : run.outcomes()) {
            if (o.success()) built.add(o.dir().toAbsolutePath().normalize());
        }
        ModuleInputProvenance.recordClasses(request.entryDir(), graph, built);
    }

    /**
     * Record every module clean under the fingerprints the preflight captured before the walk.
     *
     * <p>Those are the only inputs this build is known to have consumed. A source saved while the
     * build ran is not among them, so the next preflight misses the memo and schedules the module;
     * fingerprinting the tree now instead would record that edit as built and ship a jar without
     * it until some other change to the same module. A preflight that captured nothing certifies
     * nothing. The one part taken later is the inputs a suite was found to read, and only as the
     * suite proved them unchanged since before it ran.
     */
    static void certifyClean(
            Path entryDir,
            BuildGraph.Result graph,
            boolean skipTests,
            @Nullable String profile,
            Optional<BuildForecasting.Preflight> forecast) {
        Map<Path, String> fingerprints = SettledReads.amend(
                forecast.map(BuildForecasting.Preflight::fingerprints).orElse(Map.of()));
        if (fingerprints.isEmpty()) return;
        PreflightMemo.storeDirty(entryDir, graph, skipTests, profile, Set.of(), fingerprints);
        // One set of fingerprints, two records: the memo says these inputs are clean, and this
        // says the outputs on disk are the ones they produce. The second is what lets preflight
        // tell "nothing to do" from "the artifacts here are from another run".
        ModuleInputProvenance.record(entryDir, graph, fingerprints);
    }

    /**
     * Only a complete, unhinted run of the lifecycle through PACKAGE can certify the graph clean:
     * {@code jk build}, or any goal that builds it before its tail, since the memo the next
     * preflight reads is that lifecycle's.
     */
    static boolean shouldStoreCleanMemo(WorkspaceRequest request) {
        return request.dirtyHint() == null
                && !request.testOnly()
                && WorkspacePreflightPhase.coreTarget(request.target()) == WorkspaceTarget.PACKAGE;
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
