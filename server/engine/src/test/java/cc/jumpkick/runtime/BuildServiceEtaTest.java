// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** The whole-build history prior: a sanity anchor for the seeded ETA, one-sided by design. */
class BuildServiceEtaTest {

    private static BuildMetrics.Stats ok(long count, long avg, long min, long max) {
        return new BuildMetrics.Stats(count, count * avg, min, max);
    }

    @Test
    void no_history_leaves_the_base_untouched() {
        assertThat(BuildService.applyHistoryPrior(0, null)).isZero();
        assertThat(BuildService.applyHistoryPrior(0, BuildMetrics.Stats.EMPTY)).isZero();
        assertThat(BuildService.applyHistoryPrior(4200, BuildMetrics.Stats.EMPTY))
                .isEqualTo(4200);
    }

    @Test
    void count_up_becomes_the_historical_average_when_the_project_has_history() {
        // Cold module + uncalibratable host used to mean "count up" (0) — history beats that.
        assertThat(BuildService.applyHistoryPrior(0, ok(5, 2000, 800, 6000))).isEqualTo(2000);
    }

    @Test
    void absurd_over_estimates_clamp_down_to_twice_the_historical_max() {
        assertThat(BuildService.applyHistoryPrior(60_000, ok(5, 2000, 800, 6000)))
                .isEqualTo(12_000);
    }

    @Test
    void the_clamp_is_one_sided_and_needs_a_settled_history() {
        // Incremental runs legitimately beat the historical average — never clamp UP.
        assertThat(BuildService.applyHistoryPrior(500, ok(5, 2000, 800, 6000))).isEqualTo(500);
        // Within 2× max → trusted as-is.
        assertThat(BuildService.applyHistoryPrior(9000, ok(5, 2000, 800, 6000))).isEqualTo(9000);
        // Fewer than 3 successful builds is too thin to clamp against.
        assertThat(BuildService.applyHistoryPrior(60_000, ok(2, 2000, 800, 6000)))
                .isEqualTo(60_000);
    }

    @Test
    void host_history_fills_count_up_when_project_path_is_unknown() {
        // JK-1151: applyHistoryPrior with host-tier stats must turn base=0 into a countdown seed.
        BuildMetrics.Stats host = ok(10, 4500, 1000, 12_000);
        assertThat(BuildService.applyHistoryPrior(0, host)).isEqualTo(4500);
        assertThat(BuildService.applyHistoryPrior(3000, host)).isEqualTo(3000);
    }

    @Test
    void history_shape_keys_separate_rebuild_from_incremental() {
        var inc = new BuildService.HistoryShape(false, 4);
        var reb = new BuildService.HistoryShape(true, 200);
        assertThat(inc.kind()).isEqualTo("build");
        assertThat(reb.kind()).isEqualTo("build:rebuild");
        assertThat(inc.dirKey(Path.of("/ws"))).isEqualTo("/ws#d4");
        assertThat(reb.dirKey(Path.of("/ws"))).isEqualTo("/ws#d200");
        assertThat(new BuildService.HistoryShape(false, -1).dirKey(Path.of("/ws"))).isEqualTo("/ws");
    }

    @Test
    void rebuild_shape_blends_cold_schedule_toward_history() {
        // Schedule base 60s, trained rebuild avg 3s → pull toward history (not leave 60s).
        long blended = BuildService.applyHistoryPrior(60_000, ok(2, 3000, 2800, 3200), true);
        assertThat(blended).isLessThan(60_000);
        assertThat(blended).isGreaterThan(3000);
        // 0.3*60000 + 0.7*3000 = 20100
        assertThat(blended).isEqualTo(20_100);
        // Incremental shape keeps the old one-sided clamp rules (no blend).
        assertThat(BuildService.applyHistoryPrior(60_000, ok(2, 3000, 2800, 3200), false))
                .isEqualTo(60_000);
    }

    @Test
    void cancelled_invocation_stats_do_not_seed_eta_priors(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        // Full success then a short cancelled (Ctrl-C) wall — okAcrossShapes / applyHistoryPrior
        // must keep the full-build average, not blend the truncated cancel.
        Path metrics = dir.resolve("metrics.json");
        BuildMetrics.record(
                metrics,
                new BuildMetrics.Outcome("build", "/proj#d1", "g:n", true, false, 12_000, java.util.List.of()),
                1_000L);
        BuildMetrics.record(
                metrics,
                new BuildMetrics.Outcome("build", "/proj#d1", "g:n", false, true, 350, java.util.List.of()),
                2_000L);
        BuildMetrics m = BuildMetrics.load(metrics);
        BuildMetrics.Stats okOnly = m.okAcrossShapes("build", "/proj");
        assertThat(okOnly.count()).isEqualTo(1);
        assertThat(okOnly.avgMillis()).isEqualTo(12_000);
        // History prior for a cold schedule (base=0) uses the ok average, not the cancel wall.
        assertThat(BuildService.applyHistoryPrior(0, okOnly)).isEqualTo(12_000);
        assertThat(BuildService.applyHistoryPrior(15_000, okOnly)).isEqualTo(15_000);
    }
}
