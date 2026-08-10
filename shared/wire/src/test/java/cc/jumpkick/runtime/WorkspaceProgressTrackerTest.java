// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Remaining-work aggregate bar: percent ≈ 100 × (1 − R/R0), capped until finish. */
class WorkspaceProgressTrackerTest {

    private static final long PF = WorkspaceProgressTracker.PREFLIGHT_UNITS;
    private static final long EX = WorkspaceProgressTracker.EXECUTE_UNITS;

    @Test
    void seed_wall_starts_at_zero_execute_fraction() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.preflightComplete();
        var s = t.seedWall(60_000, 2);
        assertThat(s.R0ms()).isEqualTo(60_000);
        assertThat(s.remainingMs()).isEqualTo(60_000);
        assertThat(s.percent()).isEqualTo(0.0);
        assertThat(s.phase()).isEqualTo("execute");
        assertThat(s.denominator()).isEqualTo(EX);
    }

    @Test
    void set_remaining_mirrors_countdown_fraction() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.seedWall(100_000, 1);
        t.setRemaining(50_000); // half done
        assertThat(t.snapshot().percent()).isEqualTo(50.0);
        t.setRemaining(25_000);
        assertThat(t.snapshot().percent()).isEqualTo(75.0);
        t.setRemaining(0);
        // Cap at DISPLAY_CAP until finish
        assertThat(t.snapshot().percent()).isEqualTo(WorkspaceProgressTracker.DISPLAY_CAP);
    }

    @Test
    void finish_forces_100() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.seedWall(10_000, 1);
        t.setRemaining(0);
        var s = t.finish();
        assertThat(s.percent()).isEqualTo(100.0);
        assertThat(s.phase()).isEqualTo("done");
        assertThat(s.remainingMs()).isEqualTo(0);
    }

    @Test
    void under_prediction_grows_R0_without_reversing_bar() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.seedWall(10_000, 1);
        t.setRemaining(5_000); // 50%
        double mid = t.snapshot().percent();
        assertThat(mid).isEqualTo(50.0);
        // Reality harder: remaining jumps up
        t.setRemaining(8_000);
        // completed was 5k; new R0 = 5k+8k = 13k; frac = 5/13
        assertThat(t.snapshot().R0ms()).isEqualTo(13_000);
        assertThat(t.snapshot().remainingMs()).isEqualTo(8_000);
        // Monotonic peak holds at least prior display fraction
        assertThat(t.snapshot().percent()).isGreaterThanOrEqualTo(mid - 0.1);
    }

    @Test
    void preflight_advances_before_seed() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.preflight("lock", 1, 1);
        assertThat(t.snapshot().phase()).isEqualTo("preflight");
        assertThat(t.snapshot().numerator()).isGreaterThan(0);
        assertThat(t.snapshot().denominator()).isEqualTo(PF + WorkspaceProgressTracker.PROVISIONAL_EXECUTE_UNITS);
    }

    @Test
    void module_complete_increments_counter() {
        WorkspaceProgressTracker t = new WorkspaceProgressTracker();
        t.seedWall(1000, 3);
        t.moduleComplete("a", 0);
        t.moduleComplete("b", 0);
        assertThat(t.snapshot().modulesComplete()).isEqualTo(2);
    }

    @Test
    void progress_token_and_percent_helpers() {
        assertThat(WorkspaceProgressTracker.progressToken(100)).isEqualTo("100");
        assertThat(WorkspaceProgressTracker.percentOf(1, 3)).isEqualTo(33.3);
        assertThat(WorkspaceProgressTracker.percentOf(1, 2)).isEqualTo(50.0);
        assertThat(Double.isNaN(WorkspaceProgressTracker.percentOf(1, 0))).isTrue();
    }
}
