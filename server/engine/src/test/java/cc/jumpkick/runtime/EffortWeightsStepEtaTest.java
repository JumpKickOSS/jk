// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ETA composes measured dirty-step walls — not whole-build {@code build}/{@code build:rebuild}
 * averages.
 */
class EffortWeightsStepEtaTest {

    @Test
    void cost_from_running_steps_sums_measured_step_averages(@TempDir Path dir) throws Exception {
        Path metricsFile = dir.resolve("metrics.json");
        String eng = "/ws/engine";
        String cli = "/ws/cli";
        // engine: compile 2s + tests 40s; cli: tests 10s
        BuildMetrics.record(
                metricsFile,
                outcome("build", eng, true, 42_000, List.of(
                        sample(eng, "compile-java", 2_000),
                        sample(eng, "run-tests", 40_000))),
                1_000L);
        BuildMetrics.record(
                metricsFile,
                outcome("build", cli, true, 10_000, List.of(sample(cli, "run-tests", 10_000))),
                2_000L);
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
                Path.of(cli),
                Set.of(Path.of(eng)),
                List.of("run-tests"),
                metrics,
                null,
                List.of(),
                Map.of());

        // flatWeight(ms) * MS_PER_WEIGHT ≈ ms
        long engMs = (long) engCost.weight() * EffortWeights.MS_PER_WEIGHT;
        long cliMs = (long) cliCost.weight() * EffortWeights.MS_PER_WEIGHT;
        assertThat(engMs).isBetween(40_000L, 44_000L); // ~42s
        assertThat(cliMs).isBetween(9_000L, 11_000L); // ~10s
        assertThat(engCost.testWeight()).isGreaterThan(0);
        assertThat(cliCost.testWeight()).isEqualTo(cliCost.weight());

        // Serial tests: test floor ≈ sum of test steps (~50s).
        long serialTests = EffortWeights.scheduleMillis(
                List.of(engCost, cliCost), 8, false, false, EffortWeights.MS_PER_WEIGHT);
        assertThat(serialTests).isBetween(48_000L, 55_000L);
        // Parallel tests + list schedule: cli waits for full eng finish (scheduler done-set), so
        // wall ≈ eng(42s) + cli(10s) when serialised by the dep edge — not eng alone.
        long parallelTests = EffortWeights.scheduleMillis(
                List.of(engCost, cliCost), 8, false, true, EffortWeights.MS_PER_WEIGHT);
        assertThat(parallelTests).isBetween(50_000L, 56_000L);
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
    void metrics_step_name_maps_compile_main() {
        assertThat(EffortWeights.metricsStepName("compile-main")).isEqualTo("compile-java");
        assertThat(EffortWeights.metricsStepName("run-tests")).isEqualTo("run-tests");
    }

    @Test
    void cold_run_tests_weight_is_ballpark_not_empty_probe() {
        // Uncalibrated cold path: product baselines × cold bias, not ~5ms empty-probe residual.
        int w = EffortWeights.coldWorkWeight("run-tests", 100, 1);
        long ms = (long) w * EffortWeights.MS_PER_WEIGHT;
        // 100 methods × baseline × cold bias ≈ 14s + startup — well above 1s, well below legacy 2m.
        assertThat(ms).isGreaterThan(10_000L);
        assertThat(ms).isLessThan(40_000L);
        // Cold ETA does not credit -w parallel (prefer over-estimate on cold explain).
        int w8 = EffortWeights.coldWorkWeight("run-tests", 100, 8);
        assertThat((long) w8 * EffortWeights.MS_PER_WEIGHT).isEqualTo(ms);
    }

    private static BuildMetrics.Outcome outcome(
            String kind, String dir, boolean ok, long millis, List<BuildMetrics.StepSample> steps) {
        return new BuildMetrics.Outcome(kind, dir, "g:a", ok, false, millis, steps);
    }

    private static BuildMetrics.StepSample sample(String dir, String step, long millis) {
        return new BuildMetrics.StepSample(dir, step, "SUCCESS", millis);
    }
}
