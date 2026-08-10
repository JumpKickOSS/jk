// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Effort-weight bar slices (not residual R/R0). */
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
    void residual_annotation_does_not_drive_bar_percent() {
        // Inflated R0 must not pin the bar at 99% when residual is near zero.
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.seedWall(210_000, 2); // 3.5m open-loop seed annotation
        t.calibrate(100, 2);
        t.moduleProgress("a", 40, 10, 40); // 25% of 40 → +10 of 100 execute
        t.noteRemaining(1_000, 210_000); // residual almost done
        // Bar still weight-based (~55% with preflight band), not residual ~99%.
        assertThat(t.snapshot().percent()).isEqualTo(55.0);
        assertThat(t.snapshot().percent()).isLessThan(90.0);
        assertThat(t.snapshot().R0ms()).isEqualTo(210_000);
        assertThat(t.snapshot().remainingMs()).isEqualTo(1_000);
    }

    @Test
    void preflight_advances_before_calibrate() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.preflight("lock", 1, 1);
        assertThat(t.snapshot().phase()).isEqualTo("preflight");
        assertThat(t.snapshot().numerator()).isGreaterThan(0);
    }

    @Test
    void finish_forces_100() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.calibrate(50, 1);
        t.moduleComplete("a", 50);
        assertThat(t.finish().percent()).isEqualTo(100.0);
    }

    private static String bar(WorkspaceProgressTracker t) {
        var s = t.snapshot();
        return s.numerator() + " of " + s.denominator();
    }
}
