// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GlyphProbeTest {

    @Test
    void an_osc_50_reply_yields_the_font_whichever_terminator_it_uses() {
        assertThat(GlyphProbe.parseFont("\u001b]50;JetBrainsMono Nerd Font\u0007\u001b[0n"))
                .contains("JetBrainsMono Nerd Font");
        assertThat(GlyphProbe.parseFont("\u001b]50;Hack\u001b\\\u001b[0n")).contains("Hack");
    }

    @Test
    void a_terminal_that_only_answers_the_status_request_names_no_font() {
        assertThat(GlyphProbe.parseFont("\u001b[0n")).isEmpty();
        assertThat(GlyphProbe.parseFont("")).isEmpty();
        assertThat(GlyphProbe.parseFont("\u001b]50;?\u0007")).isEmpty();
        assertThat(GlyphProbe.parseFont("\u001b]50;\u0007")).isEmpty();
    }

    @Test
    void a_cursor_report_yields_its_column() {
        assertThat(GlyphProbe.parseCursorColumn("\u001b[12;3R")).hasValue(3);
        assertThat(GlyphProbe.parseCursorColumn("junk\u001b[1;120Rmore")).hasValue(120);
        assertThat(GlyphProbe.parseCursorColumn("\u001b[0n")).isEmpty();
        assertThat(GlyphProbe.parseCursorColumn("")).isEmpty();
    }
}
