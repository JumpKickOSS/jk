// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.plugin.build.Phase;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.run.TestSummary;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Records per-unit {@link StepTimings.Sample}s from one module's real (non-skip) step runs into a
 * shared sink; caller folds via {@link StepTimings#record} at build end. Also emits absolute-ms
 * {@link HostLearnedRates.HostSample}s for continuous host {@link Calibration}.
 *
 * <p>for {@code run-tests}, prefer the actual {@link TestSummary} method count (and class
 * count when available via display names) over planned ticks so the next plan's learned rate
 * matches real suite size.
 */
public final class StepTimingsRecorder implements BuildPlanListener {

    /** Cap a single hung suite from poisoning host method averages (~5 min/method). */
    private static final double MAX_METHOD_MS = 300_000;

    private static final double MAX_COMPILE_PER_SOURCE_MS = 60_000;
    private static final double MAX_PACKAGE_MS = 600_000;

    private final String moduleKey;
    private final List<StepTimings.Sample> sink;
    private final Map<String, Integer> ticksByStep = new ConcurrentHashMap<>();
    private final Map<String, Long> durationByStep = new ConcurrentHashMap<>();
    /** Optional supplier of the pipeline's test summary after run-tests (may be null). */
    private final Supplier<TestSummary> testSummary;
    /** Optional continuous host calibration samples (may be null). */
    private final List<HostLearnedRates.HostSample> hostSink;

    public StepTimingsRecorder(String moduleKey, List<StepTimings.Sample> sink) {
        this(moduleKey, sink, null, null);
    }

    public StepTimingsRecorder(String moduleKey, List<StepTimings.Sample> sink, Supplier<TestSummary> testSummary) {
        this(moduleKey, sink, testSummary, null);
    }

    public StepTimingsRecorder(
            String moduleKey,
            List<StepTimings.Sample> sink,
            Supplier<TestSummary> testSummary,
            List<HostLearnedRates.HostSample> hostSink) {
        this.moduleKey = moduleKey;
        this.sink = sink;
        this.testSummary = testSummary;
        this.hostSink = hostSink;
    }

    @Override
    public void stepStart(String step, Phase phase, int ticks) {
        ticksByStep.put(step, ticks);
    }

    @Override
    public void stepFinish(String step, Phase phase, TaskStatus status, Duration duration) {
        // Only successful real work teaches the ledger — CANCELLED / FAIL / SKIPPED never do.
        if (status != TaskStatus.SUCCESS || !learnable(step)) return;
        long ms = duration == null ? 0 : duration.toMillis();
        durationByStep.put(step, ms);
        // Defer all samples until pipelineFinish so a later cancel/fail drops the whole module's
        // mid-run SUCCESS ticks (estimator hygiene: only successful pipelines train rates).
    }

    @Override
    public void pipelineFinish(BuildPlanResult result) {
        // Cancelled or failed pipelines must not train rates — truncated walls poison ETA.
        if (result == null || !result.success() || result.cancelled() || result.userCancelled()) return;
        // Compile / other count-scaled steps: deferred from stepFinish.
        for (var e : durationByStep.entrySet()) {
            String step = e.getKey();
            if ("run-tests".equals(step)) continue;
            if (!learnable(step)) continue;
            int count = ticksByStep.getOrDefault(step, 0);
            long wall = e.getValue();
            double perUnit = EffortWeights.observedPerUnit(step, wall, count);
            if (perUnit > 0) {
                sink.add(new StepTimings.Sample(moduleKey, step, perUnit));
            }
            emitHostCompileOrPackage(step, wall, count);
        }
        Long ms = durationByStep.get("run-tests");
        if (ms == null) return;
        int planned = ticksByStep.getOrDefault("run-tests", 0);
        int methods = planned;
        int classes = 0;
        TestSummary sum = testSummary == null ? null : testSummary.get();
        if (sum != null && sum.total() > 0) {
            methods = (int) Math.min(Integer.MAX_VALUE, sum.total());
            // The runner counts distinct executed classes; failure-derived names were
            // empty on green runs, so the class-rate sample never recorded.
            classes = sum.classes() > 0 ? (int) Math.min(Integer.MAX_VALUE, sum.classes()) : distinctClassCount(sum);
        }
        // Prefer succeeded method count when available (skipped tests shouldn't dilute the rate).
        if (sum != null && sum.succeeded() > 0) {
            methods = (int) Math.min(Integer.MAX_VALUE, sum.succeeded());
        }
        double perMethod = EffortWeights.observedPerUnit("run-tests", ms, methods);
        if (perMethod > 0) {
            sink.add(new StepTimings.Sample(moduleKey, "run-tests", perMethod));
            // Host-wide absolute ms/method for cold modules that have never run tests here.
            if (methods > 0) {
                double msPerMethod = ms / (double) methods;
                if (msPerMethod > 0) {
                    sink.add(new StepTimings.Sample(StepTimings.HOST_METHOD_MS_DIR, "test-method-ms", msPerMethod));
                    if (hostSink != null) {
                        hostSink.add(new HostLearnedRates.HostSample(
                                HostLearnedRates.RUN_TESTS_PER_METHOD_MS, msPerMethod, MAX_METHOD_MS));
                        // Attribute fixed suite overhead when residual is positive.
                        long startup = Math.max(0, ms - Math.round(msPerMethod * methods));
                        // Prefer a modest fixed prior when residual is zero (overhead absorbed in rate).
                        if (startup <= 0) startup = Calibration.STATIC_SUITE_STARTUP_MS;
                        hostSink.add(new HostLearnedRates.HostSample(
                                HostLearnedRates.RUN_TESTS_SUITE_STARTUP_MS, startup, MAX_PACKAGE_MS));
                    }
                }
            }
        }
        // Optional class-rate sample for hierarchical lookup (stored as synthetic step key).
        if (classes > 0) {
            double perClass = EffortWeights.observedPerUnit("run-tests", ms, classes);
            if (perClass > 0) {
                sink.add(new StepTimings.Sample(moduleKey, "run-tests-class", perClass));
            }
        }
    }

    private void emitHostCompileOrPackage(String step, long wallMs, int count) {
        if (hostSink == null || wallMs <= 0) return;
        switch (step) {
            case "compile-java" ->
                perSource(HostLearnedRates.COMPILE_JAVA_PER_SOURCE_MS, wallMs, count, MAX_COMPILE_PER_SOURCE_MS);
            case "compile-kotlin" ->
                perSource(HostLearnedRates.COMPILE_KOTLIN_PER_SOURCE_MS, wallMs, count, MAX_COMPILE_PER_SOURCE_MS);
            case "compile-groovy" ->
                perSource(HostLearnedRates.COMPILE_GROOVY_PER_SOURCE_MS, wallMs, count, MAX_COMPILE_PER_SOURCE_MS);
            case "compile-test" ->
                perSource(HostLearnedRates.COMPILE_TEST_PER_SOURCE_MS, wallMs, count, MAX_COMPILE_PER_SOURCE_MS);
            case "package-jar" ->
                hostSink.add(new HostLearnedRates.HostSample(HostLearnedRates.PACKAGE_JAR_MS, wallMs, MAX_PACKAGE_MS));
            case "package-assembly" ->
                hostSink.add(
                        new HostLearnedRates.HostSample(HostLearnedRates.PACKAGE_ASSEMBLY_MS, wallMs, MAX_PACKAGE_MS));
            default -> {}
        }
    }

    private void perSource(String key, long wallMs, int count, double maxSane) {
        int n = Math.max(1, count);
        hostSink.add(new HostLearnedRates.HostSample(key, wallMs / (double) n, maxSane));
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
            case "compile-java",
                    "compile-kotlin",
                    "compile-groovy",
                    "compile-test",
                    "run-tests",
                    "package-jar",
                    "package-assembly" -> true;
            default -> false;
        };
    }
}
