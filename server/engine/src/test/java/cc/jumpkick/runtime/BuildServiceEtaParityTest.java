// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.Task;
import cc.jumpkick.wire.runtime.ExplainPlan;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

    /**
     * Regression: under --force/--redo the ETA seed comes from a shape-only plan (no
     * TaskForecaster content-prediction walk); the distrust fallback in etaCostsFromExplainPlan
     * prices each module from its full plan shape, so the seed is still non-zero.
     */
    @Test
    void force_prices_shape_only_plan_without_forecast_walk(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("mod"));
        Files.createDirectories(dir.resolve("src/main/java"));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "ex"
                name = "m"
                version = "1.0"
                java = 25
                """);
        var shapeOnly = new TaskForecast.Module(dir, "ex:m", List.of(), 0, 0, true, false);
        var plan = new ExplainPlan(List.of(shapeOnly), Map.of(dir, Set.of()), 1, List.of());

        var forced = Session.defaults()
                .withConfig(JkConfig.empty().withRebuild(true)) // same distrust lever as force
                .withCacheDir(tmp.resolve("cache"));
        List<EffortWeights.ModuleCost> costs = SessionContext.where(
                forced,
                () -> BuildService.etaCostsFromExplainPlan(plan, tmp.resolve("cache"), 1, null, null, false, false, 0));
        assertThat(costs).hasSize(1);
        assertThat(costs.get(0).weight()).isGreaterThan(0);
    }

    /**
     * Regression: a selection build's ETA seed must price only the hinted modules —
     * the whole-graph forecast would bill dirty modules the build will never schedule.
     */
    @Test
    void restrict_to_selection_keeps_only_hinted_modules_and_edges() {
        Path a = Path.of("/ws/a");
        Path b = Path.of("/ws/b");
        Path c = Path.of("/ws/c");
        var run = new TaskForecast.Task("compile-java", TaskForecast.Status.RUN, "", null);
        var cached = new TaskForecast.Task("compile-java", TaskForecast.Status.CACHED, "", "k");
        var ma = new TaskForecast.Module(a, "g:a", List.of(run), 1, 0, true, false);
        var mb = new TaskForecast.Module(b, "g:b", List.of(run), 1, 0, true, false);
        var mc = new TaskForecast.Module(c, "g:c", List.of(cached), 1, 0, true, false);
        var plan =
                new ExplainPlan(List.of(ma, mb, mc), Map.of(a, Set.of(b, c), b, Set.of(), c, Set.of()), 2, List.of());

        ExplainPlan restricted = BuildService.restrictToSelection(plan, Set.of(a, c));

        assertThat(restricted.modules()).extracting(m -> m.dir()).containsExactly(a, c);
        // Edges intersect the selection: a→{b,c} loses the unselected b.
        assertThat(restricted.edges().get(a)).containsExactly(c);
        assertThat(restricted.edges()).doesNotContainKey(b);
        // The hinted-but-clean module keeps its cache verdicts (prices ~0, not full RUN).
        assertThat(restricted.modules().get(1).dirty()).isFalse();
        assertThat(restricted.maxReadyWidth()).isEqualTo(plan.maxReadyWidth());
    }

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
        var plan = BuildPlan.builder("m")
                .addTask(Task.builder("compile-java")
                        .weight(20)
                        .execute(ctx -> {})
                        .build())
                .addTask(
                        Task.builder("run-tests").weight(100).execute(ctx -> {}).build())
                .build();
        var cost = EffortWeights.costOf(MOD, Set.of(), plan);
        assertThat(cost.weight()).isEqualTo(120);
        assertThat(cost.testWeight()).isEqualTo(100);
    }
}
