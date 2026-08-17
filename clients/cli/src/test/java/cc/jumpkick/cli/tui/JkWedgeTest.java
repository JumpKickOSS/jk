// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.theme.Theme;
import org.junit.jupiter.api.Test;

class JkWedgeTest {

    @Test
    void plain_ok_and_fail_match_legacy_chip_line() {
        RenderContext plain = RenderContext.current().withAnsi(false);
        assertThat(JkWedge.ok("Build", "done").renderLine(plain)).isEqualTo(" + Build > done");
        assertThat(JkWedge.fail("Build", "boom").renderLine(plain)).isEqualTo(" ! Build > boom");
        assertThat(JkWedge.cancelled("Build", false, "took 1s").renderLine(plain))
                .isEqualTo(" o Build > job was cancelled took 1s");
    }

    @Test
    void nerd_uses_pua_ansi_does_not() {
        if (!Theme.active().isAnsi()) return;
        String nerd =
                JkWedge.ok("Clean", "ok").renderLine(RenderContext.current().withNerd(true));
        String ansi =
                JkWedge.ok("Clean", "ok").renderLine(RenderContext.current().withNerd(false));
        assertThat(nerd).contains(Glyphs.SEGMENT_END_NERD);
        assertThat(ansi).doesNotContain(Glyphs.SEGMENT_END_NERD);
        assertThat(nerd).contains("Clean").contains("ok");
        assertThat(ansi).contains("Clean").contains("ok");
    }

    @Test
    void menu_and_warning_title_bars_fill_to_width() {
        RenderContext ctx = RenderContext.current();
        String menu = JkWedge.menu("Installed JDKs").renderTitleBar(ctx, 40);
        assertThat(TestAnsi.strip(menu)).contains("Installed JDKs");
        assertThat(RenderContext.visibleWidth(menu)).isEqualTo(40);
        String warn = JkWedge.warning("Nuke").renderTitleBar(ctx, 30);
        assertThat(TestAnsi.strip(warn)).contains("Nuke");
        assertThat(RenderContext.visibleWidth(warn)).isEqualTo(30);
    }

    @Test
    void progress_suffix_is_rich_text() {
        JkWedge w = new JkWedge(Icon.spinner(), "Build", RichText.empty())
                .progress(new Progress(1, 2).suffix(RichText.plain("ETA ~12s")));
        String line = TestAnsi.strip(w.renderLine(RenderContext.current()));
        assertThat(line).contains("Build");
        assertThat(line).contains("50%");
        assertThat(line).contains("ETA ~12s");
    }
}
