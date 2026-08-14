// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.NerdFontCaps;
import org.junit.jupiter.api.Test;

/**
 * JK-1892: plain mode is ASCII-only (docs/tui.md), and widgets rendered directly via
 * {@code Widget.render(plainCtx)} bypass the CliOutput PlainAscii boundary — so the plain
 * branches must emit ASCII themselves.
 */
class PlainWidgetGlyphTest {

    private static final RenderContext PLAIN = new RenderContext(Theme.active(), false, NerdFontCaps.NONE, 80, 0);

    @Test
    void radio_buttons_are_ascii_in_plain_mode() {
        String on = new RadioButton("alpha", true, false, "").renderInline(PLAIN);
        String off = new RadioButton("beta", false, false, "hint").renderInline(PLAIN);
        assertThat(on).startsWith("(*) alpha");
        assertThat(off).startsWith("( ) beta");
        assertThat(on + off).matches("\\p{ASCII}+");
    }

    @Test
    void checkboxes_are_ascii_in_plain_mode() {
        String on = String.join("", new Checkbox("alpha", true, false, "").render(PLAIN));
        String off = String.join("", new Checkbox("beta", false, false, "").render(PLAIN));
        assertThat(on).startsWith("[x] alpha");
        assertThat(off).startsWith("[ ] beta");
        assertThat(on + off).matches("\\p{ASCII}+");
    }

    @Test
    void plain_ascii_maps_the_radio_off_circle_as_a_safety_net() {
        assertThat(PlainAscii.transform("○")).isEqualTo("o");
    }
}
