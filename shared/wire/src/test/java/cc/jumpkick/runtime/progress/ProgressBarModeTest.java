// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.progress;

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
}
