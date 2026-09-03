// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime.progress;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProgressBarModeTest {

    @Test
    void parse_and_select() {
        assertThat(ProgressBarMode.parse(null)).isEqualTo(ProgressBarMode.AUTO);
        assertThat(ProgressBarMode.parse("clock")).isEqualTo(ProgressBarMode.CLOCK);
        assertThat(ProgressBarMode.parse("weighted")).isEqualTo(ProgressBarMode.WEIGHTED);
        var c = new ClockProgressStrategy();
        var w = new WeightedProgressStrategy();
        assertThat(ProgressBarMode.AUTO.select(c, w, 1)).isSameAs(c);
        assertThat(ProgressBarMode.AUTO.select(c, w, 0)).isSameAs(w);
    }

    @Test
    void forced_clock_falls_back_weighted_only_when_it_has_nothing_to_paint() {
        // No R0 and no residual → a clock bar would sit frozen at 0%; fall back.
        var c = new ClockProgressStrategy();
        var w = new WeightedProgressStrategy();
        assertThat(ProgressBarMode.CLOCK.select(c, w, 0, -1)).isSameAs(w);
        assertThat(ProgressBarMode.CLOCK.select(c, w, 1, -1)).isSameAs(c);
        assertThat(ProgressBarMode.CLOCK.select(c, w, 0, 5_000)).isSameAs(c);
    }

    @Test
    void clock_residual_only_drains_instead_of_freezing() {
        // Residual-only (forced clock, no R0): elapsed advances against the residual estimate.
        var c = new ClockProgressStrategy();
        long[] early = c.display(new HeaderProgressState(0, 0, -1, 0, 10_000, 90_000, false));
        assertThat(early[0]).isEqualTo(100); // 10s / (10s + 90s)
        long[] later = c.display(new HeaderProgressState(0, 0, -1, 0, 60_000, 40_000, false));
        assertThat(later[0]).isEqualTo(600);
    }
}
