// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.theme.Theme;
import org.junit.jupiter.api.Test;

class BoxTableTest {

    @Test
    void title_bar_carries_title_and_has_no_top_left_corner() {
        String line = JkWedge.menu("Installed OpenJDKs").renderTitleBar(RenderContext.current(), 50);
        String plain = stripAnsi(line);
        assertThat(plain).contains("Installed OpenJDKs");
        assertThat(plain).doesNotContain("╭");
        // Ends with box top-right (ANSI) or ASCII +
        assertThat(plain.endsWith("╮") || plain.endsWith("+")).isTrue();
        if (!Theme.active().isAnsi()) {
            assertThat(plain).startsWith(" = Installed OpenJDKs > ");
            assertThat(plain.length()).isEqualTo(50);
        } else {
            assertThat(plain).contains(Glyphs.MENU);
            assertThat(plain.length()).isEqualTo(50);
        }
    }

    @Test
    void command_wedge_menu_is_blue_chip_shape() {
        String line = CommandWedge.menu("Installed OpenJDKs");
        String plain = stripAnsi(line);
        assertThat(plain).contains("Installed OpenJDKs");
        if (Theme.active().isAnsi()) {
            assertThat(plain).contains(Glyphs.MENU);
        } else {
            assertThat(plain).isEqualTo(" = Installed OpenJDKs >");
        }
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[0-9;]*m", "");
    }
}
