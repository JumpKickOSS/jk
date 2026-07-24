// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.run.PipelineListener;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.run.StepStatus;
import cc.jumpkick.run.TestSummary;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Records per-unit {@link StepTimings.Sample}s from one module's real (non-skip) step runs into a
 * shared sink; caller folds via {@link StepTimings#record} at build end.
 *
 * <p>JK-1155: for {@code run-tests}, prefer the actual {@link TestSummary} method count (and class
 * count when available via display names) over planned ticks so the next plan's learned rate
 * matches real suite size.
 */
public final class StepTimingsRecorder implements PipelineListener {

    private final String moduleKey;
    private final List<StepTimings.Sample> sink;
    private final Map<String, Integer> ticksByStep = new ConcurrentHashMap<>();
    private final Map<String, Long> durationByStep = new ConcurrentHashMap<>();
    /** Optional supplier of the pipeline's test summary after run-tests (may be null). */
    private final Supplier<TestSummary> testSummary;

    public StepTimingsRecorder(String moduleKey, List<StepTimings.Sample> sink) {
        this(moduleKey, sink, null);
    }

    public StepTimingsRecorder(String moduleKey, List<StepTimings.Sample> sink, Supplier<TestSummary> testSummary) {
        this.moduleKey = moduleKey;
        this.sink = sink;
        this.testSummary = testSummary;
    }

    @Override
    public void stepStart(String step, Phase phase, int ticks) {
        ticksByStep.put(step, ticks);
    }

    @Override
    public void stepFinish(String step, Phase phase, StepStatus status, Duration duration) {
        if (status != StepStatus.SUCCESS || !learnable(step)) return;
        long ms = duration == null ? 0 : duration.toMillis();
        durationByStep.put(step, ms);
        // Defer run-tests until pipelineFinish so TestSummary is available (JK-1155).
        if ("run-tests".equals(step)) return;
        int count = ticksByStep.getOrDefault(step, 0);
        double perUnit = EffortWeights.observedPerUnit(step, ms, count);
        if (perUnit > 0) {
            sink.add(new StepTimings.Sample(moduleKey, step, perUnit));
        }
    }

    @Override
    public void pipelineFinish(PipelineResult result) {
        if (result == null || !result.success()) return;
        Long ms = durationByStep.get("run-tests");
        if (ms == null) return;
        int planned = ticksByStep.getOrDefault("run-tests", 0);
        int methods = planned;
        int classes = 0;
        TestSummary sum = testSummary == null ? null : testSummary.get();
        if (sum != null && sum.total() > 0) {
            methods = (int) Math.min(Integer.MAX_VALUE, sum.total());
            // Distinct class names from failure records are incomplete; use success-path heuristic:
            // when failures list class names on any entry, prefer that set size only if larger than 0
            // and we have no better signal. Primary signal is method total.
            classes = distinctClassCount(sum);
        }
        double perMethod = EffortWeights.observedPerUnit("run-tests", ms, methods);
        if (perMethod > 0) {
            sink.add(new StepTimings.Sample(moduleKey, "run-tests", perMethod));
        }
        // Optional class-rate sample for hierarchical lookup (stored as synthetic step key).
        if (classes > 0) {
            double perClass = EffortWeights.observedPerUnit("run-tests", ms, classes);
            if (perClass > 0) {
                sink.add(new StepTimings.Sample(moduleKey, "run-tests-class", perClass));
            }
        }
    }

    private static int distinctClassCount(TestSummary sum) {
        if (sum.failures() == null || sum.failures().isEmpty()) return 0;
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (TestSummary.Failure f : sum.failures()) {
            if (f.className() != null && !f.className().isBlank()) names.add(f.className());
        }
        return names.size();
    }

    /** The variable, count-scaled steps whose duration is worth learning. */
    private static boolean learnable(String step) {
        return switch (step) {
            case "compile-java", "compile-kotlin", "compile-test", "run-tests" -> true;
            default -> false;
        };
    }
}
