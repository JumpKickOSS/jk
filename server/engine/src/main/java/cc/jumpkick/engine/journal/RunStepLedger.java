// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import cc.jumpkick.engine.api.BuildHistoryKinds;
import cc.jumpkick.runtime.base.RunShapes;
import cc.jumpkick.runtime.base.StepLedger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A finished run's steps into its project's {@link StepLedger}, under the shape it ran with. */
final class RunStepLedger {

    private RunStepLedger() {}

    /**
     * Record {@code finished} into the ledger under {@code projectHome}: successful build-like runs
     * only, as their metrics are; a failed or cancelled run's walls are not what its steps cost.
     */
    static void record(Path projectHome, BuildRecord finished) {
        if (finished == null || finished.running() || !finished.success() || finished.cancelled()) return;
        if (!BuildHistoryKinds.isBuildLike(finished.kind())) return;
        List<StepLedger.Sample> samples = new ArrayList<>();
        if (finished.steps() != null && finished.dir() != null) {
            for (BuildRecord.Task t : finished.steps()) samples.add(sample(finished.dir(), t));
        }
        if (finished.modules() != null) {
            for (BuildRecord.Module m : finished.modules()) {
                if (m == null || m.dir() == null || m.steps() == null) continue;
                for (BuildRecord.Task t : m.steps()) samples.add(sample(m.dir(), t));
            }
        }
        StepLedger.append(projectHome, RunShapes.of(finished.requestId()), samples);
    }

    private static StepLedger.Sample sample(String dir, BuildRecord.Task t) {
        return new StepLedger.Sample(dir, t.name(), t.status(), Math.max(0, t.millis()));
    }
}
