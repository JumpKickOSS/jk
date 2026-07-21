// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.run.PipelineListener;
import cc.jumpkick.run.StepStatus;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records per-unit {@link StepTimings.Sample}s from one module's real (non-skip) step runs into a
 * shared sink; caller folds via {@link StepTimings#record} at build end.
 */
public final class StepTimingsRecorder implements PipelineListener {

    private final String moduleKey;
    private final List<StepTimings.Sample> sink;
    private final Map<String, Integer> ticksByStep = new ConcurrentHashMap<>();

    public StepTimingsRecorder(String moduleKey, List<StepTimings.Sample> sink) {
        this.moduleKey = moduleKey;
        this.sink = sink;
    }

    @Override
    public void stepStart(String step, Phase phase, int ticks) {
        ticksByStep.put(step, ticks);
    }

    @Override
    public void stepFinish(String step, Phase phase, StepStatus status, Duration duration) {
        if (status != StepStatus.SUCCESS || !learnable(step)) return;
        int count = ticksByStep.getOrDefault(step, 0);
        double perUnit = EffortWeights.observedPerUnit(step, duration.toMillis(), count);
        if (perUnit > 0) {
            sink.add(new StepTimings.Sample(moduleKey, step, perUnit));
        }
    }

    /** The variable, count-scaled steps whose duration is worth learning. */
    private static boolean learnable(String step) {
        return switch (step) {
            case "compile-java", "compile-kotlin", "compile-test", "run-tests" -> true;
            default -> false;
        };
    }
}
