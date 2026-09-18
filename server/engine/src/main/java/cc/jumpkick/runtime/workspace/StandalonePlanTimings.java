// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.Calibration;
import cc.jumpkick.runtime.base.HostLearnedRates;
import cc.jumpkick.runtime.base.StepTimings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a standalone project's plan learns from a successful run, the same lessons a workspace
 * member's plan teaches: its {@link StepTimingsRecorder} samples fold into the step-timings ledger
 * under the cache, its host samples into {@link Calibration}, and its per-class test walls are
 * buffered for the run's {@code metrics.toml} — the data the next ETA and the results' slow-class
 * hints read. The recorder is attached before the fold, so it has spoken when the fold listens.
 */
public final class StandalonePlanTimings {

    private StandalonePlanTimings() {}

    /**
     * Attach the recorder and its fold to {@code plan}, the project at {@code dir} built over
     * {@code cache}; {@code clock} stamps the folded rates.
     */
    public static void attach(BuildPlan plan, Path dir, Path cache, Clock clock) {
        List<StepTimings.Sample> samples = Collections.synchronizedList(new ArrayList<>());
        List<HostLearnedRates.HostSample> host = Collections.synchronizedList(new ArrayList<>());
        plan.addListener(new StepTimingsRecorder(
                dir.toString(),
                samples,
                () -> plan.get(BuildPlanner.TEST_RESULT).orElse(null),
                host));
        plan.addListener(new BuildPlanListener() {
            @Override
            public void planFinish(BuildPlanResult result) {
                if (result == null || !result.success() || result.cancelled() || result.userCancelled()) return;
                StepTimings.record(cache, List.copyOf(samples), StepTimings.DEFAULT_ALPHA, clock.millis());
                Calibration.learnFromSuccess(List.copyOf(host));
            }
        });
    }
}
