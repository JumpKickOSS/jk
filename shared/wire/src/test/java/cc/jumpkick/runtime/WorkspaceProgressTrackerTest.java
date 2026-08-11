// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Weight slices for wire num/den; percent via ProgressBarMode (clock when R0 set). */
class WorkspaceProgressTrackerTest {

    private static final long PF = WorkspaceProgressTracker.PREFLIGHT_UNITS;

    @Test
    void without_r0_percent_is_weighted() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.calibrate(100, 2);
        assertThat(t.progressStrategyId()).isEqualTo("weighted");
        t.moduleProgress("a", 40, 10, 40); // +10 of 100 execute
        // preflight 100 + 10 of execute / (100 + 100) = 55%
        assertThat(t.snapshot().percent()).isEqualTo(55.0);
    }

    @Test
    void with_r0_percent_is_clock_not_weight_race() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.seedWall(100_000, 2); // R0 = 100s
        t.calibrate(100, 2);
        assertThat(t.progressStrategyId()).isEqualTo("clock");
        // Weight would jump if we raced modules; clock stays near 0 just after seed.
        t.moduleProgress("a", 40, 40, 40);
        t.moduleProgress("b", 60, 60, 60);
        double pct = t.snapshot().percent();
        assertThat(pct).isLessThan(5.0); // just seeded — not 100% weight fill
    }

    @Test
    void residual_annotation_does_not_replace_clock_when_r0_set() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.seedWall(210_000, 2);
        t.calibrate(100, 2);
        t.moduleProgress("a", 40, 10, 40);
        t.noteRemaining(1_000, 210_000);
        // Clock strategy, not residual 99% or weight 55%
        assertThat(t.progressStrategyId()).isEqualTo("clock");
        assertThat(t.snapshot().R0ms()).isEqualTo(210_000);
        assertThat(t.snapshot().remainingMs()).isEqualTo(1_000);
        assertThat(t.snapshot().percent()).isLessThan(90.0);
    }

    @Test
    void finish_forces_100() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.calibrate(50, 1);
        t.moduleComplete("a", 50);
        assertThat(t.finish().percent()).isEqualTo(100.0);
    }

    @Test
    void preflight_advances_before_calibrate() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.preflight("lock", 1, 1);
        assertThat(t.snapshot().phase()).isEqualTo("preflight");
        assertThat(t.snapshot().numerator()).isGreaterThan(0);
    }

    @Test
    void weight_numerator_still_tracks_modules_when_clock_paints_percent() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.seedWall(60_000, 1);
        t.calibrate(100, 1);
        t.moduleProgress("a", 100, 50, 100);
        var s = t.snapshot();
        // Clock percent is time-based; strategy display may use scale 1000
        assertThat(s.percent()).isNotNaN();
        assertThat(t.executeTotal()).isEqualTo(100);
    }
}
