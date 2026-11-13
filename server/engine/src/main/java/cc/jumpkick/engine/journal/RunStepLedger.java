// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.engine.api.BuildHistoryKinds;
import cc.jumpkick.engine.plugin.MemoryHolds;
import cc.jumpkick.runtime.StepEffort;
import cc.jumpkick.runtime.base.RunShapes;
import cc.jumpkick.runtime.base.RungBias;
import cc.jumpkick.runtime.base.StepLedger;
import cc.jumpkick.wire.runtime.StepCost;
import cc.jumpkick.wire.runtime.WorkModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/** A finished run's steps as its project's {@link StepLedger} entry, under the shape it ran with. */
final class RunStepLedger {

    private RunStepLedger() {}

    /**
     * The ledger entry of {@code finished}, for its project store to apply ({@link
     * StepLedger#entry}), and what it teaches the host's effort and rung-bias sections: successful
     * build-like runs only; a failed or cancelled run's walls are not what its steps cost. Null when
     * there is nothing to record.
     */
    static @Nullable UnaryOperator<List<String>> record(BuildRecord finished) {
        if (finished == null || finished.running() || !finished.success() || finished.cancelled()) return null;
        if (!BuildHistoryKinds.isBuildLike(finished.kind())) return null;
        // A run that went on hold for memory spent its steps' long waits paused, not working: those
        // waits come off the walls the ledger learns (JK-VYJ4X).
        boolean held = MemoryHolds.totalHeldMillis(finished.requestId()) > 0;
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
                RunShapes.curvesOf(finished.requestId()),
                RunShapes.workersOf(finished.requestId()),
                RunShapes.compilerWarmthOf(finished.requestId()),
                RunShapes.hostBusyOf(finished.requestId()),
                samples);
        StepEffort.learn(StepEffort.defaultFile(), effortSamples(samples));
        RungBias.learn(RungBias.defaultFile(), pricedPairs(finished.requestId(), samples));
        return entry;
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
        for (StepLedger.Sample s : samples) {
            if (!"SUCCESS".equals(s.status())) continue;
            StepCost c = EtaModels.priced(requestId, s.dir(), s.step());
            if (c == null) continue;
            pairs.add(new RungBias.Pair(
                    c.rung(), s.step(), (long) c.weight() * WorkModel.MS_PER_WEIGHT, s.ms(), c.spread()));
        }
        return pairs;
    }

    /**
     * The step's sample: its wall, less its wait when the run was {@code held} and the wait was long
     * enough to be a hold ({@link MemoryHolds#HOLD_AFTER_NANOS}). A short wait is ordinary queueing
     * and part of what the step costs.
     */
    static StepLedger.Sample sample(String dir, BuildRecord.Task t, boolean held) {
        long wall = Math.max(0, t.millis());
        long wait = Math.max(0, t.waitMillis());
        if (held && wait * 1_000_000L >= MemoryHolds.HOLD_AFTER_NANOS) wall = Math.max(0, wall - wait);
        return new StepLedger.Sample(dir, t.name(), t.status(), wall);
    }
}
