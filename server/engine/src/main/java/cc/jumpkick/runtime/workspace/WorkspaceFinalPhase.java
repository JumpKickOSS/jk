// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.orchestrator.TestClassMatch;
import cc.jumpkick.orchestrator.TestRun;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.Calibration;
import cc.jumpkick.runtime.MemoAudit;
import cc.jumpkick.runtime.ModuleInputProvenance;
import cc.jumpkick.runtime.PreflightMemo;
import cc.jumpkick.runtime.SettledReads;
import cc.jumpkick.runtime.base.RunMeasures;
import cc.jumpkick.runtime.base.StepTimings;
import cc.jumpkick.task.VerifyCache;
import cc.jumpkick.wire.runtime.BuildKnobs;
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
        if (!decision.learn()) {
            WorkspaceRequest request = run.prepared().resources().request();
            PreflightMemo.markDirty(request.entryDir(), unfinished(run));
            // A red test run still left the outputs of the modules it finished green: without their
            // record the next forecast calls every module "built from other inputs".
            if (request.testOnly() && !run.cancelled()) {
                certifyTestRun(
                        run.prepared().resources().preflight(),
                        run.outcomes(),
                        ModuleInputProvenance.RunTrees.transforms(request.cache()));
            }
            return decision.result();
        }
        WorkspaceResult noMatch = noClassMatched(run);
        if (noMatch != null) return noMatch;
        WorkspaceResult diverged = cacheDivergence(run);
        if (diverged != null) return diverged;
        learn(run);
        return decision.result();
    }

    /** The modules the run did not finish green: failed, cancelled, or the one the scheduler failed. */
    static Set<Path> unfinished(WorkspaceRunPhase.Run run) {
        Set<Path> out = new HashSet<>();
        for (ModuleOutcome o : run.outcomes()) {
            if (!o.success()) out.add(o.dir());
        }
        run.scheduleFailure().ifPresent(o -> out.add(o.dir()));
        return out;
    }

    /**
     * The one run-wide failure a green run can still turn into: every module skipped the
     * {@code --class} patterns. Each module only knows its own suite; this is where their answers
     * meet. {@code null} when some module matched, or the run selected no classes.
     */
    static @Nullable WorkspaceResult noClassMatched(WorkspaceRunPhase.Run run) {
        List<TestRun> runs = run.prepared().plans().values().stream()
                .map(ModulePlan::plan)
                .flatMap(plan -> plan.get(BuildPlanner.TEST_RUN).stream())
                .toList();
        boolean skipTests = run.prepared().resources().request().skipTests();
        String verdict = TestClassMatch.runWideVerdict(SessionContext.current(), skipTests, runs);
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
        long now = Clock.SYSTEM.millis();

        // An estimate still being priced when the build ended has nothing to learn from: it was
        // never shown, and its schedule was never the one this run was measured against.
        resources.eta().close().ifPresent(model -> {
            if (!model.measured()) {
                // Nothing measured to protect yet: a cold worker's compile walls are the history
                // there is (a CI agent with a fresh engine per job has no other).
                RunMeasures measures = RunMeasures.current();
                if (measures != null) measures.keepCompileWalls();
            }
        });
        StepTimings.record(request.cache(), resources.timingSamples(), StepTimings.DEFAULT_ALPHA, now);
        Calibration.learnFromSuccess(List.copyOf(resources.hostSamples()));
        medianRate(run.observedRates()).ifPresent(rate -> Calibration.refine(rate, now));

        if (shouldStoreCleanMemo(request)) {
            // Under the knobs the preflight read the memo with: the goal's test policy applied.
            BuildKnobs knobs = BuildKnobs.of(request);
            certifyClean(
                    request.entryDir(),
                    graph,
                    knobs.skipTests(),
                    knobs.profile(),
                    resources.preflight().forecast(),
                    ModuleInputProvenance.RunTrees.transforms(request.cache()));
        } else if (request.testOnly()) {
            certifyTestRun(
                    resources.preflight(), run.outcomes(), ModuleInputProvenance.RunTrees.transforms(request.cache()));
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
     * Record every module clean under the fingerprints the preflight captured before the walk, but
     * one whose suite reached another host and stored no marker, which is recorded dirty.
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
        certifyClean(entryDir, graph, skipTests, profile, forecast, ModuleInputProvenance.RunTrees.NONE);
    }

    /**
     * As above, recording the trees {@code runTrees} names with the outputs: a classes transform's,
     * which the compile goal rewrites behind a memo that still says these inputs are clean.
     */
    static void certifyClean(
            Path entryDir,
            BuildGraph.Result graph,
            boolean skipTests,
            @Nullable String profile,
            Optional<BuildForecasting.Preflight> forecast,
            ModuleInputProvenance.RunTrees runTrees) {
        Map<Path, String> preflight =
                forecast.map(BuildForecasting.Preflight::fingerprints).orElse(Map.of());
        Map<Path, String> fingerprints = SettledReads.amend(preflight);
        if (fingerprints.isEmpty()) return;
        // A suite that reached another host stored no marker: its module stays dirty.
        PreflightMemo.storeDirty(entryDir, graph, skipTests, profile, SettledReads.takeUnreplayable(), fingerprints);
        MemoAudit.auditCertified(skipTests, preflight, fingerprints);
        // One set of fingerprints, two records: the memo says these inputs are clean, and this
        // says the outputs on disk are the ones they produce. The second is what lets preflight
        // tell "nothing to do" from "the artifacts here are from another run".
        ModuleInputProvenance.record(entryDir, graph, fingerprints, runTrees);
    }

    /**
     * Record the outputs a green test run left as built from the inputs it ran on, for every module
     * it tested and whose jar it packaged or which has none: {@code jk test} runs every module's
     * steps through its jar for the siblings that read it, so its outputs are the ones those inputs
     * produce, and with no record the next forecast called every module "built from other inputs"
     * until a {@code jk build} (hotel-reservations after a relock and a green {@code jk test
     * -g}, and on a fresh clone). A module the run tested without packaging keeps the record it had:
     * a jar there may be an older build's. Only the provenance record is written; the clean memo
     * stays the build's, which runs the whole lifecycle.
     */
    static void certifyTestRun(
            WorkspacePreflightPhase.Context preflight,
            List<ModuleOutcome> outcomes,
            ModuleInputProvenance.RunTrees runTrees) {
        List<Path> green = new ArrayList<>();
        for (ModuleOutcome o : outcomes) {
            if (o.success()) green.add(o.dir());
        }
        ModuleInputProvenance.recordTestRun(
                preflight.request().entryDir(),
                preflight.graph(),
                preflight.testRunFingerprints(),
                green,
                preflight.jarConsumed(),
                runTrees);
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
