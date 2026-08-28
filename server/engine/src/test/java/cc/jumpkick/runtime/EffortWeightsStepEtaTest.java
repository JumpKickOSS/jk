// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.Task;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ETA composes measured dirty-step walls — not whole-build {@code build}/{@code build:rebuild}
 * averages.
 */
class EffortWeightsStepEtaTest {

    @TempDir
    Path hostStateDir;

    private String prevStateDir;
    private String prevBuildsDir;

    /**
     * Pin state + builds, the same way {@link NativeEffortTest} does and for the same reason. Cold
     * pricing consults {@code Calibration.load()} before its uncalibrated baselines, so without
     * this the assertions here read the developer's real {@code host-metrics.toml} and change
     * meaning the moment that host is calibrated — {@code cached_steps_excluded_from_cost} expects
     * the uncalibrated {@code 2 * TOKEN} and saw {@code 10}.
     *
     * <p>{@code Calibration.installForTest} is not enough on its own: tests share a JVM, and
     * another class's {@code invalidateMemo()} drops the installed value mid-test. Moving the
     * directory the memo would reload from is what makes it stick. JK_BUILDS_DIR is required too:
     * a set JK_HOME would otherwise ignore JK_STATE_DIR for builds/.
     */
    @BeforeEach
    void isolateHostState() {
        prevStateDir = System.getProperty("jk.env.JK_STATE_DIR");
        prevBuildsDir = System.getProperty("jk.env.JK_BUILDS_DIR");
        System.setProperty("jk.env.JK_STATE_DIR", hostStateDir.toString());
        System.setProperty("jk.env.JK_BUILDS_DIR", hostStateDir.resolve("builds").toString());
        Calibration.invalidateMemo();
        BuildMetrics.clearSessionAggregatesMemo();
    }

    @AfterEach
    void restoreHostState() {
        if (prevStateDir == null) System.clearProperty("jk.env.JK_STATE_DIR");
        else System.setProperty("jk.env.JK_STATE_DIR", prevStateDir);
        if (prevBuildsDir == null) System.clearProperty("jk.env.JK_BUILDS_DIR");
        else System.setProperty("jk.env.JK_BUILDS_DIR", prevBuildsDir);
        Calibration.invalidateMemo();
        BuildMetrics.clearSessionAggregatesMemo();
    }

    @Test
    void cost_from_running_steps_sums_measured_step_averages(@TempDir Path dir) throws Exception {
        Path metricsFile = dir.resolve("metrics.json");
        String eng = "/ws/engine";
        String cli = "/ws/cli";
        // engine: compile 2s + tests 40s; cli: tests 10s
        BuildMetrics.record(
                metricsFile,
                outcome(
                        "build",
                        eng,
                        true,
                        42_000,
                        List.of(sample(eng, "compile-java", 2_000), sample(eng, "run-tests", 40_000))),
                1_000L);
        BuildMetrics.record(
                metricsFile, outcome("build", cli, true, 10_000, List.of(sample(cli, "run-tests", 10_000))), 2_000L);
        BuildMetrics metrics = BuildMetrics.load(metricsFile);

        var engCost = EffortWeights.costFromRunningSteps(
                Path.of(eng),
                Set.of(),
                List.of("compile-main", "run-tests"), // forecast name maps to compile-java
                metrics,
                null,
                List.of(),
                Map.of());
        var cliCost = EffortWeights.costFromRunningSteps(
                Path.of(cli), Set.of(Path.of(eng)), List.of("run-tests"), metrics, null, List.of(), Map.of());

        // flatWeight(ms) * MS_PER_WEIGHT ≈ ms
        long engMs = (long) engCost.weight() * EffortWeights.MS_PER_WEIGHT;
        long cliMs = (long) cliCost.weight() * EffortWeights.MS_PER_WEIGHT;
        assertThat(engMs).isBetween(40_000L, 44_000L); // ~42s
        assertThat(cliMs).isBetween(9_000L, 11_000L); // ~10s
        assertThat(engCost.testWeight()).isGreaterThan(0);
        assertThat(cliCost.testWeight()).isEqualTo(cliCost.weight());

        // Serial tests: test floor ≈ sum of test steps (~50s).
        long serialTests =
                EffortWeights.scheduleMillis(List.of(engCost, cliCost), 8, false, false, EffortWeights.MS_PER_WEIGHT);
        assertThat(serialTests).isBetween(48_000L, 55_000L);
        // Phase-gated schedule (JK-2210/2211): cli admits at eng's ARTIFACT point (~2s — the
        // compile slice; eng's 40s suite overlaps), so wall ≈ eng alone (~42s), not 42+10.
        long parallelTests =
                EffortWeights.scheduleMillis(List.of(engCost, cliCost), 8, false, true, EffortWeights.MS_PER_WEIGHT);
        assertThat(parallelTests).isBetween(40_000L, 44_000L);
    }

    @Test
    void cold_module_run_tests_prefers_the_count_scaled_host_prior(@TempDir Path dir) throws Exception {
        Path metricsFile = dir.resolve("metrics.json");
        // Host suite-wall history from another (small) module: ~3s.
        BuildMetrics.record(
                metricsFile,
                outcome("build", "/ws/other", true, 3_000, List.of(sample("/ws/other", "run-tests", 3_000))),
                1_000L);
        BuildMetrics metrics = BuildMetrics.load(metricsFile);
        // Host ms/method prior: 5 ms per successful test method.
        Path cache = dir.resolve("cache");
        StepTimings.record(
                cache,
                List.of(new StepTimings.Sample(StepTimings.HOST_METHOD_MS_DIR, "test-method-ms", 5.0)),
                StepTimings.DEFAULT_ALPHA,
                1_000L);
        StepTimings timings = StepTimings.load(cache);

        // A brand-new 2000-method module scales with its count (~10s), not the host's ~3s
        // average suite.
        var cost = EffortWeights.costFromRunningSteps(
                Path.of("/ws/new"),
                Set.of(),
                List.of("run-tests"),
                metrics,
                timings,
                List.of(),
                Map.of("run-tests", 2_000));
        long ms = (long) cost.weight() * EffortWeights.MS_PER_WEIGHT;
        assertThat(ms).isBetween(9_500L, 13_000L);

        // No count known → the host suite average is still the fallback.
        var noCount = EffortWeights.costFromRunningSteps(
                Path.of("/ws/new"), Set.of(), List.of("run-tests"), metrics, timings, List.of(), Map.of());
        long noCountMs = (long) noCount.weight() * EffortWeights.MS_PER_WEIGHT;
        assertThat(noCountMs).isBetween(2_500L, 3_600L);
    }

    @Test
    void list_schedule_respects_concurrency_and_full_prereq_completion() {
        // Three independent 30-unit modules + one dependent on all three.
        Path a = Path.of("/a"), b = Path.of("/b"), c = Path.of("/c"), d = Path.of("/d");
        var costs = List.of(
                new EffortWeights.ModuleCost(a, Set.of(), 30, 0),
                new EffortWeights.ModuleCost(b, Set.of(), 30, 0),
                new EffortWeights.ModuleCost(c, Set.of(), 30, 0),
                new EffortWeights.ModuleCost(d, Set.of(a, b, c), 10, 0));
        // Concurrency 1 → serial 100.
        assertThat(EffortWeights.listSchedule(costs, 1)).isEqualTo(100);
        // Concurrency 3 → roots in parallel (30) then d (10) = 40.
        assertThat(EffortWeights.listSchedule(costs, 3)).isEqualTo(40);
        // Concurrency 2 → roots take two waves (30+30) then d = 70.
        assertThat(EffortWeights.listSchedule(costs, 2)).isEqualTo(70);
    }

    @Test
    void cached_steps_excluded_from_cost() {
        // No metrics file — empty metrics; cold TOKEN steps don't invent cost.
        BuildMetrics metrics = BuildMetrics.load(Path.of("/nonexistent-" + System.nanoTime()));
        var cost = EffortWeights.costFromRunningSteps(
                Path.of("/ws/lib"),
                Set.of(),
                List.of("parse-build", "write-stamp"),
                metrics,
                null,
                List.of(),
                Map.of());
        // TOKEN weights only
        assertThat(cost.weight()).isEqualTo(2 * EffortWeights.TOKEN);
        assertThat(cost.testWeight()).isZero();
    }

    @Test
    void restore_outputs_prices_as_restore_not_zero_or_compile() {
        BuildMetrics metrics = BuildMetrics.load(Path.of("/nonexistent-" + System.nanoTime()));
        var cost = EffortWeights.costFromRunningSteps(
                Path.of("/ws/lib"), Set.of(), List.of("restore-outputs"), metrics, null, List.of(), Map.of());
        assertThat(cost.weight()).isEqualTo(EffortWeights.RESTORE);
        assertThat(cost.testWeight()).isZero();
        // Far below a cold compile wall for a non-trivial source count.
        assertThat(cost.weight()).isLessThan(EffortWeights.coldWorkWeight("compile-java", 50));
    }

    @Test
    void metrics_step_name_maps_compile_main() {
        assertThat(EffortWeights.metricsStepName("compile-main")).isEqualTo("compile-java");
        assertThat(EffortWeights.metricsStepName("run-tests")).isEqualTo("run-tests");
    }

    @Test
    void cold_run_tests_weight_is_ballpark_not_empty_probe() {
        // Use empty calibration so host product layout priors do not affect product baseline math.
        Calibration.installForTest(Calibration.absentForTest());
        try {
            int w = EffortWeights.coldWorkWeight("run-tests", 100, 1);
            long ms = (long) w * EffortWeights.MS_PER_WEIGHT;
            // 100 methods × baseline (~45ms) + suite startup — seconds, not sub-second or minutes.
            assertThat(ms).isGreaterThan(3_000L);
            assertThat(ms).isLessThan(25_000L);
            // Cold ETA caps within-module -w (COLD_MAX_TEST_PARALLEL), not full linear speedup.
            int w8 = EffortWeights.coldWorkWeight("run-tests", 100, 8);
            long ms8 = (long) w8 * EffortWeights.MS_PER_WEIGHT;
            assertThat(ms8).isLessThan(ms);
            assertThat(ms8).isGreaterThan(ms / (Calibration.COLD_MAX_TEST_PARALLEL + 1L));
        } finally {
            Calibration.clearMemo();
        }
    }

    @Test
    void cold_reprice_without_counts_underprices_tests_that_counts_fix() {
        // Regression: build countdown used costFromRunningSteps(..., Map.of) while explain passed
        // testCount → cold monorepo ETA collapsed to suite-startup × modules (~12s) vs minutes.
        BuildMetrics metrics = BuildMetrics.load(Path.of("/nonexistent-" + System.nanoTime()));
        var withCounts = EffortWeights.costFromRunningSteps(
                Path.of("/ws/cli"),
                Set.of(),
                List.of("compile-java", "run-tests", "package-jar"),
                metrics,
                null,
                List.of(),
                Map.of("compile-java", 227, "run-tests", 884));
        var withoutCounts = EffortWeights.costFromRunningSteps(
                Path.of("/ws/cli"),
                Set.of(),
                List.of("compile-java", "run-tests", "package-jar"),
                metrics,
                null,
                List.of(),
                Map.of());
        long withMs = (long) withCounts.weight() * EffortWeights.MS_PER_WEIGHT;
        long withoutMs = (long) withoutCounts.weight() * EffortWeights.MS_PER_WEIGHT;
        // Count-aware cold pricing for ~884 tests is tens of seconds+; empty counts is seconds.
        assertThat(withMs).isGreaterThan(20_000L);
        assertThat(withoutMs).isLessThan(15_000L);
        assertThat(withMs).isGreaterThan(withoutMs * 5);
    }

    @Test
    void step_counts_and_running_steps_come_from_the_prepared_pipeline() {
        var plan = BuildPlan.builder("m")
                .addTask(Task.builder("parse-build")
                        .weight(EffortWeights.TOKEN)
                        .ticks(1)
                        .execute(ctx -> {})
                        .build())
                .addTask(Task.builder("compile-java")
                        .weight(40)
                        .ticks(227)
                        .execute(ctx -> {})
                        .build())
                .addTask(Task.builder("run-tests")
                        .weight(800)
                        .ticks(884)
                        .execute(ctx -> {})
                        .build())
                .build();
        assertThat(EffortWeights.runningStepsFromBuildPlan(plan))
                .containsExactly("compile-java", "run-tests"); // TOKEN parse-build omitted
        // Counts include every step with ticks>0 (harmless extras); pricing only uses running steps.
        assertThat(EffortWeights.stepCountsFromBuildPlan(plan))
                .containsEntry("compile-java", 227)
                .containsEntry("run-tests", 884)
                .containsEntry("parse-build", 1);
    }

    private static BuildMetrics.Outcome outcome(
            String kind, String dir, boolean ok, long millis, List<BuildMetrics.StepSample> steps) {
        return new BuildMetrics.Outcome(kind, dir, "g:a", ok, false, millis, steps);
    }

    private static BuildMetrics.StepSample sample(String dir, String step, long millis) {
        return new BuildMetrics.StepSample(dir, step, "SUCCESS", millis);
    }
}
