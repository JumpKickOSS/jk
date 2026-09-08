package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.runtime.base.BuildMetrics;
import org.junit.jupiter.api.Test;

class ApplyHistoryPriorTest {

    @Test
    void does_not_clamp_to_subsecond_history_max() {
        // Poisoned 99ms "full build" samples must not collapse a 2-minute composition.
        BuildMetrics.Stats poison = new BuildMetrics.Stats(5, 99 * 5, 50, 99);
        long base = 125_000L;
        assertThat(BuildService.applyHistoryPrior(base, poison)).isEqualTo(base);
    }

    @Test
    void still_clamps_absurd_over_estimate_against_credible_history() {
        BuildMetrics.Stats hist = new BuildMetrics.Stats(5, 60_000L * 5, 50_000, 70_000);
        long absurd = 10 * 60_000L; // 10 minutes vs 70s max
        assertThat(BuildService.applyHistoryPrior(absurd, hist)).isEqualTo(2 * 70_000L);
    }
}
