// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** JK-1120 — engine aggregate bar model (ported from former CLI AggregateContext tests). */
class WorkspaceProgressTrackerTest {

    private static final long PF = WorkspaceProgressTracker.PREFLIGHT_UNITS;

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
        var s = t.preflight("plan", 0, 10);
        assertThat(s.denominator()).isEqualTo(PF);
        assertThat(s.numerator()).isGreaterThan(0);
        assertThat(s.phase()).isEqualTo("preflight");
        s = t.preflight("plan", 10, 10);
        assertThat(s.numerator()).isEqualTo(PF);
        s = t.calibrate(200, 3);
        assertThat(bar(t)).isEqualTo(PF + " of " + (PF + 200));
        assertThat(s.phase()).isEqualTo("execute");
        assertThat(s.modulesTotal()).isEqualTo(3);
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
        assertThat(s.progressToken()).isEqualTo("100");
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
