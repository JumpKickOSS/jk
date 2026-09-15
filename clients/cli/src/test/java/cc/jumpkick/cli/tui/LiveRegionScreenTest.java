// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.terminal.Ansi;
import org.junit.jupiter.api.Test;

/** Geometry of {@link LiveRegionScreen}: wrap, scroll, and CSI the plan painter emits. */
class LiveRegionScreenTest {

    @Test
    void newline_at_the_last_row_scrolls_the_top_into_scrollback() {
        var s = new LiveRegionScreen(3, 8);
        s.write("one\n");
        s.write("two\n");
        s.write("three\n");
        s.write("four\n");
        // Four content lines + newline on a 3-row pane: two in scrollback, two visible, park empty.
        assertThat(s.scrollback()).containsExactly("one", "two");
        assertThat(s.viewport()).containsExactly("three", "four", "");
    }

    @Test
    void cursor_up_then_erase_display_clears_the_region_and_below() {
        var s = new LiveRegionScreen(4, 10);
        s.write("aaaa\nbbbb\ncccc\n");
        s.write(Ansi.cursorUp(2) + "\r" + Ansi.ERASE_DISPLAY_TO_END);
        s.write("xxxx\n");
        assertThat(s.row(0)).isEqualTo("aaaa");
        assertThat(s.row(1)).isEqualTo("xxxx");
        assertThat(s.row(2)).isEmpty();
        assertThat(s.row(3)).isEmpty();
    }

    @Test
    void a_full_width_write_wraps_onto_the_next_physical_row() {
        var s = new LiveRegionScreen(3, 4);
        s.write("abcdef");
        assertThat(s.row(0)).isEqualTo("abcd");
        assertThat(s.row(1)).isEqualTo("ef");
    }
}
