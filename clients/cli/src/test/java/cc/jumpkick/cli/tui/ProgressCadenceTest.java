// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProgressCadenceTest {

    @Test
    void render_includes_percent_and_suffix() {
        String line = new Progress(2, 5).suffix("ETA ~12s").render(RenderContext.current());
        assertThat(cc.jumpkick.cli.TestAnsi.strip(line)).contains("40%").contains("ETA ~12s");
    }

    @Test
    void canonical_constructor_normalizes_every_component() {
        // The canonical constructor is public by record rule, so it is API: null/non-positive
        // components must land normalized, exactly as the two-arg convenience and withers do.
        Progress p = new Progress(2, 5, null, null, 0);
        assertThat(p.suffix()).isEqualTo(RichText.empty());
        assertThat(p.look()).isEqualTo(Progress.Look.PLAN);
        assertThat(p.segments()).isEqualTo(Progress.DEFAULT_SEGMENTS);
        assertThat(p).isEqualTo(new Progress(2, 5));
    }
}
