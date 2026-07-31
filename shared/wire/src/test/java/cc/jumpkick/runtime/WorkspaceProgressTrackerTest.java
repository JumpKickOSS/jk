// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**engine aggregate bar model (ported from former CLI AggregateContext tests). */
class WorkspaceProgressTrackerTest {

    private static final long PF = WorkspaceProgressTracker.PREFLIGHT_UNITS;

    @Test
    void denominator_shrink_clamps_slice_and_total_non_negative() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.calibrate(50, 1);
        t.moduleProgress("a", 50, 10, 100);
        // Shrink far below the registered slice — must clamp, not go negative.
        var s = t.moduleProgress("a", 50, 1, 2);
        assertThat(t.executeTotal()).isGreaterThanOrEqualTo(0);
        assertThat(s.numerator()).isGreaterThanOrEqualTo(0);
        assertThat(s.denominator()).isGreaterThanOrEqualTo(PF);
    }

    @Test
    void calibrated_bar_keeps_a_fixed_denominator_and_advances_cumulatively() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.calibrate(100, 2);
        assertThat(t.executeTotal()).isEqualTo(100);
        assertThat(bar(t)).isEqualTo(PF + " of " + (PF + 100));

        t.moduleProgress("a", 40, 0, 40);
        assertThat(bar(t)).isEqualTo(PF + " of " + (PF + 100));
        t.moduleProgress("a", 40, 10, 40); // 25% of slice → +10
        assertThat(bar(t)).isEqualTo((PF + 10) + " of " + (PF + 100));
        t.moduleComplete("a", 40);

        t.moduleProgress("b", 60, 0, 60);
        assertThat(bar(t)).isEqualTo((PF + 40) + " of " + (PF + 100));
        t.moduleProgress("b", 60, 30, 60);
        assertThat(bar(t)).isEqualTo((PF + 70) + " of " + (PF + 100));
    }

    @Test
    void concurrent_modules_sum_their_slices() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.calibrate(100, 2);
        t.moduleProgress("a", 40, 20, 40); // +20
        t.moduleProgress("b", 60, 30, 60); // +30
        assertThat(bar(t)).isEqualTo((PF + 50) + " of " + (PF + 100));
    }

    @Test
    void overrun_clamps_to_the_slice_and_never_grows_the_total() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.calibrate(50, 1);
        t.moduleProgress("m", 50, 80, 50); // frac clamped to 1.0 → +50
        assertThat(bar(t)).isEqualTo((PF + 50) + " of " + (PF + 50));
    }

    @Test
    void module_boundary_does_not_backtrack_when_a_module_overruns() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.calibrate(100, 2);
        t.moduleProgress("a", 40, 70, 40);
        assertThat(bar(t)).isEqualTo((PF + 40) + " of " + (PF + 100));
        t.moduleComplete("a", 40);
        t.moduleProgress("b", 60, 0, 60);
        assertThat(bar(t)).isEqualTo((PF + 40) + " of " + (PF + 100));
    }

    @Test
    void uncalibrated_falls_back_to_the_growing_per_module_total() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.moduleProgress("a", 0, 0, 40);
        assertThat(bar(t)).isEqualTo("0 of 40");
        t.moduleProgress("a", 0, 10, 40);
        assertThat(bar(t)).isEqualTo("10 of 40");
        t.moduleComplete("a", 40);
        t.moduleProgress("b", 0, 0, 60);
        assertThat(bar(t)).isEqualTo("40 of 100");
    }

    @Test
    void module_reweight_resizes_its_slice_and_the_aggregate_total() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.calibrate(100, 2);
        t.moduleProgress("a", 40, 0, 40);
        assertThat(t.executeTotal()).isEqualTo(100);
        t.moduleProgress("a", 40, 3, 3); // den 40→3, total 100−37=63
        assertThat(t.executeTotal()).isEqualTo(63);
        t.moduleComplete("a", 3);
        assertThat(bar(t)).isEqualTo((PF + 3) + " of " + (PF + 63));
        t.moduleProgress("b", 60, 60, 60);
        assertThat(bar(t)).isEqualTo((PF + 63) + " of " + (PF + 63));
    }

    @Test
    void preflight_advances_reserved_units_before_calibrate() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        long provisionalDen = PF + WorkspaceProgressTracker.PROVISIONAL_EXECUTE_UNITS;
        var s = t.preflight("plan", 0, 10);
        // Preflight uses a provisional execute band so the bar is never "almost full" then snap-back.
        assertThat(s.denominator()).isEqualTo(provisionalDen);
        assertThat(s.numerator()).isGreaterThan(0);
        assertThat(s.phase()).isEqualTo("preflight");
        assertThat(s.percent()).isLessThan(20.0);
        s = t.preflight("plan", 10, 10);
        // Plan-complete stays below the full band and well under half the provisional bar.
        assertThat(s.numerator()).isEqualTo(95);
        assertThat(s.percent()).isLessThan(15.0);
        double preflightPeak = s.percent();
        s = t.calibrate(200, 3);
        // Calibrate must not flash the bar backward (peak-hold across den growth).
        assertThat(s.percent()).isGreaterThanOrEqualTo(preflightPeak);
        assertThat(s.denominator()).isEqualTo(PF + 200);
        assertThat(s.phase()).isEqualTo("execute");
        assertThat(s.modulesTotal()).isEqualTo(3);
    }

    @Test
    void preflight_never_fills_the_bar_before_calibrate() {
        // First-build flash regression: lock/graph/plan against den=PREFLIGHT alone looked ~100%.
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        for (String stage : new String[] {"checking", "lock", "graph", "plan"}) {
            var s = t.preflight(stage, 1, 1);
            assertThat(s.percent())
                    .as("stage %s must stay a small early slice", stage)
                    .isLessThan(20.0);
        }
        var before = t.snapshot();
        t.calibrate(5_000, 12);
        assertThat(t.snapshot().percent()).isGreaterThanOrEqualTo(before.percent());
        assertThat(t.snapshot().percent()).isLessThan(25.0);
    }

    @Test
    void calibrate_zero_weight_floors_to_module_count_tokens() {
        // /1154: empty execute weight with N modules still calibrates so module
        // boundaries cannot fall into uncalibrated "reset" math.
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.calibrate(0, 3);
        assertThat(t.executeTotal()).isEqualTo(3);
        assertThat(t.calibrated()).isTrue();
        t.moduleProgress("a", 1, 1, 1);
        t.moduleComplete("a", 1);
        var mid = t.snapshot();
        assertThat(mid.numerator()).isGreaterThan(0);
        t.moduleProgress("b", 1, 0, 1);
        assertThat(t.snapshot().numerator())
                .isGreaterThanOrEqualTo(mid.numerator() - WorkspaceProgressTracker.PREFLIGHT_UNITS);
        // Absolute: after A complete, base holds A's slice even when B starts at 0 frac.
        assertThat(bar(t)).isEqualTo((PF + 1) + " of " + (PF + 3));
    }

    @Test
    void module_a_to_b_never_zeros_numerator_after_progress() {
        // regression: monorepo module swap must keep completed work on the bar.
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.calibrate(200, 2);
        t.moduleProgress("a", 100, 100, 100);
        t.moduleComplete("a", 100);
        long afterA = t.snapshot().numerator();
        assertThat(afterA).isEqualTo(PF + 100);
        t.moduleProgress("b", 100, 0, 100);
        assertThat(t.snapshot().numerator()).isEqualTo(afterA);
        assertThat(t.snapshot().numerator()).isNotEqualTo(0);
    }

    @Test
    void monotonic_peak_holds_when_fraction_would_slide() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.calibrate(100, 1);
        t.moduleProgress("m", 100, 50, 100);
        long peak = t.snapshot().numerator();
        // Same total, lower raw fraction — peak hold keeps numerator up.
        t.moduleProgress("m", 100, 40, 100);
        assertThat(t.snapshot().numerator()).isEqualTo(peak);
    }

    @Test
    void finish_forces_one_hundred() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.calibrate(50, 1);
        t.moduleProgress("m", 50, 25, 50);
        var s = t.finish();
        assertThat(s.percent()).isEqualTo(100.0);
        assertThat(s.phase()).isEqualTo("done");
        assertThat(WorkspaceProgressTracker.progressToken(s.percent())).isEqualTo("100");
    }

    @Test
    void percent_rounding_one_decimal() {
        assertThat(WorkspaceProgressTracker.percentOf(1, 3)).isEqualTo(33.3);
        assertThat(WorkspaceProgressTracker.percentOf(1, 2)).isEqualTo(50.0);
        assertThat(Double.isNaN(WorkspaceProgressTracker.percentOf(1, 0))).isTrue();
    }

    private static String bar(WorkspaceProgressTracker t) {
        var s = t.snapshot();
        return s.numerator() + " of " + s.denominator();
    }
}
