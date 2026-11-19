// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.engine.api.BuildHistoryKinds;
import cc.jumpkick.engine.plugin.MemoryHolds;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.StepEffort;
import cc.jumpkick.runtime.base.RunShapes;
import cc.jumpkick.runtime.base.RungBias;
import cc.jumpkick.runtime.base.StepLedger;
import cc.jumpkick.wire.runtime.StepCost;
import cc.jumpkick.wire.runtime.WorkModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/** A finished run's steps as its project's {@link StepLedger} entry, under the shape it ran with. */
final class RunStepLedger {

    private RunStepLedger() {}

    /**
     * The ledger entry of {@code finished}, for its project store to apply ({@link
     * StepLedger#entry}), and what it teaches the host's effort and rung-bias sections: successful
     * build-like runs only; a failed or cancelled run's walls are not what its steps cost. Null when
     * there is nothing to record. The host's sections go to {@code hostStore}, the store of the
     * builds root the run is journaled under, not to the one the process resolves now: a run
     * finished under one state directory once taught another's estimates.
     */
    static @Nullable UnaryOperator<List<String>> record(BuildRecord finished, Path hostStore) {
        if (finished == null || finished.running() || !finished.success() || finished.cancelled()) return null;
        if (!BuildHistoryKinds.isBuildLike(finished.kind())) return null;
        long held = MemoryHolds.totalHeldMillis(finished.requestId());
        List<StepLedger.Sample> samples = new ArrayList<>();
        if (finished.steps() != null && finished.dir() != null) {
            for (BuildRecord.Task t : finished.steps()) samples.add(sample(finished.dir(), t, held));
        }
        if (finished.modules() != null) {
            for (BuildRecord.Module m : finished.modules()) {
                if (m == null || m.dir() == null || m.steps() == null) continue;
                for (BuildRecord.Task t : m.steps()) samples.add(sample(m.dir(), t, held));
            }
        }
        UnaryOperator<List<String>> entry = StepLedger.entry(
                RunShapes.of(finished.requestId()),
                RunShapes.frontierOf(finished.requestId()),
                withoutSuspendedSuites(RunShapes.curvesOf(finished.requestId()), samples),
                withoutSuspendedSuites(RunShapes.workersOf(finished.requestId()), samples),
                RunShapes.compilerWarmthOf(finished.requestId()),
                RunShapes.hostBusyOf(finished.requestId()),
                samples);
        StepEffort.learn(hostStore, effortSamples(samples));
        RungBias.learn(hostStore, pricedPairs(finished.requestId(), samples));
        return entry;
    }

    /** {@code byDir} less the suites that ran across a suspend: their curve and their walls are the gap's. */
    static <V> Map<String, V> withoutSuspendedSuites(Map<String, V> byDir, List<StepLedger.Sample> samples) {
        if (byDir == null || byDir.isEmpty()) return byDir;
        Map<String, V> out = new HashMap<>(byDir);
        for (StepLedger.Sample s : samples) {
            if (StepLedger.SUSPENDED.equals(s.status()) && TaskNames.isRunTests(s.step())) out.remove(s.dir());
        }
        return out;
    }

    /** The run's finished node steps and plugin goals, each with its measure in its module ({@link StepEffort}). */
    static List<StepEffort.Sample> effortSamples(List<StepLedger.Sample> samples) {
        List<StepEffort.Sample> out = new ArrayList<>();
        for (StepLedger.Sample s : samples) {
            if (!"SUCCESS".equals(s.status()) || !StepEffort.prices(s.step())) continue;
            out.add(new StepEffort.Sample(s.step(), StepEffort.measure(s.step(), Path.of(s.dir())), s.ms()));
        }
        return out;
    }

    /**
     * The run's steps that ran and that its seed priced: the rung that priced each, its price and its
     * wall and the spread it was priced with, which teach the host how far each layer misses and how
     * its misses go together ({@link RungBias}).
     */
    static List<RungBias.Pair> pricedPairs(long requestId, List<StepLedger.Sample> samples) {
        List<RungBias.Pair> pairs = new ArrayList<>();
        boolean slowRun = RunShapes.of(requestId).slowTier();
        for (StepLedger.Sample s : samples) {
            if (!"SUCCESS".equals(s.status())) continue;
            StepCost c = EtaModels.priced(requestId, s.dir(), s.step());
            if (c == null) continue;
            // A suite of a tier the default run leaves out teaches its own kind (RungBias.biasStep).
            boolean slowTier = slowRun && TaskNames.isRunTests(s.step());
            pairs.add(new RungBias.Pair(
                    c.rung(),
                    RungBias.biasStep(s.step(), slowTier),
                    (long) c.weight() * WorkModel.MS_PER_WEIGHT,
                    s.ms(),
                    c.spread()));
        }
        return pairs;
    }

    /**
     * The step's sample: its wall, less its wait up to the {@code heldMillis} the run spent on hold. A
     * fork's wait counts only its time with the run on hold, so a run never on hold keeps every wall
     * whole: a wait for a JVM slot, or for memory while other forks ran, is part of what a step costs.
     */
    static StepLedger.Sample sample(String dir, BuildRecord.Task t, long heldMillis) {
        long wall = Math.max(0, t.millis());
        long off = Math.min(Math.max(0, t.waitMillis()), Math.max(0, heldMillis));
        // A step the engine stopped running across measured the time nothing ran: the ledger keeps
        // that it ran, under a status no price reads, so it teaches the ledger, the effort section
        // and the rung bias nothing while the steps before and after the gap teach them as usual.
        String status = t.suspended() && "SUCCESS".equals(t.status()) ? StepLedger.SUSPENDED : t.status();
        return new StepLedger.Sample(dir, t.name(), status, Math.max(0, wall - off));
    }
}
