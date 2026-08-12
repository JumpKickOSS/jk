// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Ansi;
import cc.jumpkick.cli.theme.Theme;
import org.junit.jupiter.api.Test;

class RichTextRenderTest {

    @Test
    void visible_width_ignores_markup_and_counts_cjk() {
        assertThat(RichText.parse("[bold]hi[/]").visibleWidth()).isEqualTo(2);
        assertThat(RichText.plain("构建").visibleWidth()).isEqualTo(4);
    }

    @Test
    void plain_mode_drops_sgr_and_rewrites_ellipsis() {
        RenderContext plain = RenderContext.current().withAnsi(false);
        String out = RichText.parse("[bold]wait…[/]").render(plain);
        assertThat(out).isEqualTo("wait...");
        assertThat(out).doesNotContain("\u001B");
    }

    @Test
    void ansi_mode_emits_sgr_and_osc8_for_links() {
        if (!Theme.active().isAnsi()) return;
        RenderContext ctx = RenderContext.current().withAnsi(true).withNerd(false);
        String colored = RichText.parse("[yellow]amber[/]").render(ctx);
        assertThat(colored).contains("amber").contains("\u001B[");
        String link = RichText.parse("[link https://example]go[/]").render(ctx);
        assertThat(link).contains("go");
        assertThat(link).contains(Ansi.OSC + "8;;https://example");
    }

    @Test
    void prestyled_ansi_passthrough_still_measures_visible_width() {
        String painted = Theme.colorize("xy", Theme.active().warning());
        RichText t = RichText.ansi(painted);
        assertThat(t.visibleWidth()).isEqualTo(2);
    }
}
