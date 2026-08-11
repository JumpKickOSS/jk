// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.progress;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProgressBarModeTest {

    @Test
    void parse_env_aliases() {
        assertThat(ProgressBarMode.parse(null)).isEqualTo(ProgressBarMode.AUTO);
        assertThat(ProgressBarMode.parse("")).isEqualTo(ProgressBarMode.AUTO);
        assertThat(ProgressBarMode.parse("clock")).isEqualTo(ProgressBarMode.CLOCK);
        assertThat(ProgressBarMode.parse("open-loop")).isEqualTo(ProgressBarMode.CLOCK);
        assertThat(ProgressBarMode.parse("weighted")).isEqualTo(ProgressBarMode.WEIGHTED);
        assertThat(ProgressBarMode.parse("work")).isEqualTo(ProgressBarMode.WEIGHTED);
        assertThat(ProgressBarMode.parse("nope")).isEqualTo(ProgressBarMode.AUTO);
    }

    @Test
    void auto_selects_clock_when_r0_present() {
        var clock = new ClockProgressStrategy();
        var weighted = new WeightedProgressStrategy();
        assertThat(ProgressBarMode.AUTO.select(clock, weighted, 60_000)).isSameAs(clock);
        assertThat(ProgressBarMode.AUTO.select(clock, weighted, 0)).isSameAs(weighted);
        assertThat(ProgressBarMode.AUTO.select(clock, weighted, -1)).isSameAs(weighted);
        assertThat(ProgressBarMode.WEIGHTED.select(clock, weighted, 60_000)).isSameAs(weighted);
        assertThat(ProgressBarMode.CLOCK.select(clock, weighted, 0)).isSameAs(clock);
    }
}
