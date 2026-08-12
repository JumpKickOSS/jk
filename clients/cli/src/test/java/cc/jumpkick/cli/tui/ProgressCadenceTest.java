// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProgressCadenceTest {

    @Test
    void plain_emits_twenty_percent_steps_not_clock_ticks() {
        assertThat(Progress.shouldEmitPlain(-1, 0)).isTrue();
        assertThat(Progress.shouldEmitPlain(0, 19)).isFalse();
        assertThat(Progress.shouldEmitPlain(0, 20)).isTrue();
        assertThat(Progress.shouldEmitPlain(20, 39)).isFalse();
        assertThat(Progress.shouldEmitPlain(20, 40)).isTrue();
        assertThat(Progress.shouldEmitPlain(80, 99)).isFalse();
        assertThat(Progress.shouldEmitPlain(80, 100)).isFalse();
    }

    @Test
    void render_includes_percent_and_suffix() {
        String line = new Progress(2, 5).suffix("ETA ~12s").render(RenderContext.current());
        assertThat(cc.jumpkick.cli.TestAnsi.strip(line)).contains("40%").contains("ETA ~12s");
    }
}
