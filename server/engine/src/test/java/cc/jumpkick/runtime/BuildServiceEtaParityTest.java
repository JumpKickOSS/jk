// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@code jk explain} and {@code jk build}'s initial countdown must share one ETA routine: same
 * {@code costFromRunningSteps} (with unit counts) and the same {@code seedEta} schedule + history
 * prior. Historical divergences:
 *
 * <ul>
 *   <li>Splitting {@code ModulePlan.weight()} from a separately estimated run-tests slice
 *       (explain≈11s / countdown≈30s).
 *   <li>Build re-pricing dirty steps with {@code Map.of()} counts while explain passed method /
 *       source counts (explain≈5m / countdown≈12s on a cold calibrated host).
 * </ul>
 */
class BuildServiceEtaParityTest {

    private static final Path MOD = Path.of("/ws/cli");

    @Test
    void shape_style_cost_keeps_weight_and_test_weight_coupled() {
        // Shape row: total 869, tests 841 (matches monorepo jk-cli shape-memo).
        var coupled = EffortWeights.costOf(MOD, Set.of(), 869, 841);
        assertThat(coupled.weight()).isEqualTo(869);
        assertThat(coupled.testWeight()).isEqualTo(841);

        // The bug: total from shape, testWeight lost (0) → critical path = full weight.
        var split = EffortWeights.costOf(MOD, Set.of(), 869, 0);
        assertThat(split.testWeight()).isZero();

        long coupledMs = EffortWeights.scheduleMillis(List.of(coupled), 11, false, true, 150);
        long splitMs = EffortWeights.scheduleMillis(List.of(split), 11, false, true, 150);
        // Single module: list schedule = full weight (tests included). Never the old throughput-only
        // ~12s that dropped the 841-unit test slice.
        assertThat(coupledMs).isEqualTo(869 * 150L);
        assertThat(splitMs).isEqualTo(869 * 150L);
    }

    @Test
    void history_clamp_still_bounds_absurd_over_estimates() {
        long absurd = 869 * 150L; // ~130s
        BuildMetrics.Stats d1 = new BuildMetrics.Stats(4, 46_180, 730, 15_794);
        long clamped = BuildService.applyHistoryPrior(absurd, d1);
        assertThat(clamped).isEqualTo(2 * 15_794L); // 31588 ms ≈ 31s

        long modest = 79 * 150L; // ~11.8s — under 2× max, no clamp
        assertThat(BuildService.applyHistoryPrior(modest, d1)).isEqualTo(modest);
    }

    @Test
    void pipeline_cost_of_derives_test_weight_from_the_same_walk() {
        var pipeline = cc.jumpkick.run.Pipeline.builder("m")
                .addStep(cc.jumpkick.run.Step.builder("compile-java")
                        .weight(20)
                        .execute(ctx -> {})
                        .build())
                .addStep(cc.jumpkick.run.Step.builder("run-tests")
                        .weight(100)
                        .execute(ctx -> {})
                        .build())
                .build();
        var cost = EffortWeights.costOf(MOD, Set.of(), pipeline);
        assertThat(cost.weight()).isEqualTo(120);
        assertThat(cost.testWeight()).isEqualTo(100);
    }

    @Test
    void mixed_history_floors_cold_tests_with_the_shape_test_weight() {
        // Compile-warm / test-cold: costFromRunningSteps with no counts priced run-tests as
        // suite startup only (testWeight 3) while the shape's coupled count-aware pair says
        // 841 — the collapsed cold test ETA the full-work floor forbids.
        var repriced = new EffortWeights.ModuleCost(MOD, Set.of(), 50, 3);
        var floored = BuildService.floorColdTests(repriced, /* runTestsOwnMillis */ 0, /* shape */ 841);
        assertThat(floored.testWeight()).isEqualTo(841);
        assertThat(floored.weight()).isEqualTo(50 - 3 + 841); // non-test share preserved

        // Own run-tests history wins over the shape floor (measured beats estimated).
        assertThat(BuildService.floorColdTests(repriced, 12_000, 841)).isSameAs(repriced);
        // Skip-tests shapes carry testWeight 0 — never floor.
        assertThat(BuildService.floorColdTests(repriced, 0, 0)).isSameAs(repriced);
    }
}
