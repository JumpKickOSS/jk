// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Ansi;
import org.junit.jupiter.api.Test;

/**
 * OSC sequences (OSC-8 hyperlinks, taskbar progress) are invisible, but JLine's
 * {@code stripAnsi} leaves them in place — a link span inside a width-managed widget would
 * inflate the measured width by the URL plus escape bytes and blow out column sizing.
 */
class RenderContextWidthTest {

    @Test
    void hyperlink_width_is_the_visible_text_only() {
        String linked = Ansi.hyperlink("https://example.com/very/long/url", "text");
        assertThat(linked.length()).isGreaterThan(30);
        assertThat(RenderContext.visibleWidth(linked)).isEqualTo(4);
        assertThat(RenderContext.stripAnsi(linked)).isEqualTo("text");
    }

    @Test
    void st_terminated_osc_is_stripped_too() {
        String st = "\u001b]8;;https://example.com\u001b\\text\u001b]8;;\u001b\\";
        assertThat(RenderContext.visibleWidth(st)).isEqualTo(4);
    }

    @Test
    void rich_text_plain_text_drops_hyperlink_bytes() {
        RichText rt = RichText.ansi(Ansi.hyperlink("https://example.com", "dash"));
        assertThat(rt.plainText()).isEqualTo("dash");
        assertThat(rt.visibleWidth()).isEqualTo(4);
    }

    @Test
    void csi_stripping_still_works() {
        assertThat(RenderContext.visibleWidth("\u001b[31mred\u001b[0m")).isEqualTo(3);
        assertThat(RenderContext.visibleWidth(null)).isZero();
        assertThat(RenderContext.visibleWidth("")).isZero();
    }
}
