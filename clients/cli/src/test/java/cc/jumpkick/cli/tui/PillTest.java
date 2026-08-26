// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.theme.Theme;
import org.junit.jupiter.api.Test;

class PillTest {

    @Test
    void plain_is_brackets() {
        String s = Pill.of("Fully Cached").renderInline(RenderContext.current().withAnsi(false));
        assertThat(s).isEqualTo("[Fully Cached]");
    }

    @Test
    void branded_plain_is_brackets() {
        String s = Pill.branded("jk-core").renderInline(RenderContext.current().withAnsi(false));
        assertThat(s).isEqualTo("[jk-core]");
    }

    @Test
    void cancelled_matches_explain_pill_gray() {
        assertThat(Pill.cancelled("Cancel").look()).isEqualTo(Pill.Look.CANCELLED);
        if (!Theme.active().isAnsi()) return;
        var ctx = RenderContext.current().withAnsi(true);
        assertThat(Pill.cancelled("Cancel").renderInline(ctx))
                .isEqualTo(Pill.of("Cancel").renderInline(ctx));
    }

    @Test
    void nerd_uses_half_circles_ansi_does_not() {
        if (!Theme.active().isAnsi()) return;
        String nerd = Pill.of("Rebuild")
                .renderInline(RenderContext.current().withAnsi(true).withNerd(true));
        String ansi = Pill.of("Rebuild")
                .renderInline(RenderContext.current().withAnsi(true).withNerd(false));
        assertThat(nerd)
                .contains(Glyphs.PILL_LEFT_NERD)
                .contains(Glyphs.PILL_RIGHT_NERD)
                .contains("Rebuild");
        assertThat(ansi).doesNotContain(Glyphs.PILL_LEFT_NERD);
        assertThat(ansi).contains("Rebuild");
    }
}
