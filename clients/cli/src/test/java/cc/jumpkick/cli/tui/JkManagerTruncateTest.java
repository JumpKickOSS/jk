// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.theme.Theme;
import org.junit.jupiter.api.Test;

/** Visible-width truncation and row column budget of the JkManager component. */
class JkManagerTruncateTest {

    @Test
    void truncate_visible_overflow_by_one_still_shows_ellipsis() {
        // Content one column over the budget must not fill the row with raw text and drop ….
        for (int n = 2; n <= 40; n++) {
            String cut = JkManager.truncateVisible("x".repeat(n + 1), n);
            assertThat(RenderContext.stripAnsi(cut)).isEqualTo("x".repeat(n - 1) + "…");
            assertThat(RenderContext.visibleWidth(cut)).isEqualTo(n);
        }
    }

    @Test
    void row_column_budget_leaves_last_column_free() {
        assertThat(JkManagerColor.rowColumnBudget(80)).isEqualTo(79);
        assertThat(JkManagerColor.rowColumnBudget(2)).isEqualTo(1);
        assertThat(JkManagerColor.rowColumnBudget(1)).isEqualTo(1);
        assertThat(JkManagerColor.rowColumnBudget(0)).isEqualTo(1);
    }

    @Test
    void truncate_visible_cuts_at_column_keeping_escapes() {
        String colored = Theme.colorize("abcdef", Theme.active().success());
        // Hard-truncate: reserve one column for … so the line never wraps.
        String cut = JkManager.truncateVisible(colored, 3);
        assertThat(TestAnsi.strip(cut)).isEqualTo("ab…");
        assertThat(cut).endsWith("\033[0m"); // reset appended on truncation
    }

    @Test
    void truncate_visible_returns_verbatim_when_it_fits() {
        String colored = Theme.colorize("abcdef", Theme.active().success());
        // Fits in 6 columns → original bytes preserved exactly (jk's SGR byte order).
        assertThat(JkManager.truncateVisible(colored, 6)).isEqualTo(colored);
        assertThat(JkManager.truncateVisible("plain", 10)).isEqualTo("plain");
    }

    @Test
    void truncate_visible_drops_control_characters_instead_of_emitting_them() {
        // A stray tab/backspace/CR in a step message (wcwidth -1) copied at weight 0 advances
        // real terminal columns past the charged budget — the row wraps and desyncs cursor
        // bookkeeping. Controls are dropped, never forwarded.
        assertThat(JkManager.truncateVisible("ab\tcd\re", 10)).isEqualTo("abcde");
        assertThat(JkManager.truncateVisible("a\bb", 2)).isEqualTo("ab");
        assertThat(RenderContext.visibleWidth(JkManager.truncateVisible("a\tb\tc", 3)))
                .isEqualTo(3);
    }

    @Test
    void truncate_visible_zero_width_tail_is_not_an_ellipsis_reserve() {
        // Zero-width code points cost no columns: base+combining at exact budget must render
        // fully — reserving an ellipsis column for the tail cut the last glyph one early.
        String cafe = "cafe\u0301"; // e + combining acute, 4 columns
        assertThat(JkManager.truncateVisible(cafe, 4)).isEqualTo(cafe);
        String sun = "\u2600\uFE0F"; // emoji + VS16 at its exact width
        assertThat(JkManager.truncateVisible(sun, 1)).isEqualTo(sun);
        assertThat(JkManager.truncateVisible("abc\t", 3)).isEqualTo("abc"); // dropped-control tail
        // A tail that still costs columns keeps the reserve.
        assertThat(TestAnsi.strip(JkManager.truncateVisible("cafe\u0301s", 4))).isEqualTo("caf…");
    }

    @Test
    void truncate_visible_one_column_keeps_a_fitting_char() {
        // Degenerate 1-column width: fitting content survives; only longer input degrades
        // to the bare ellipsis. Empty stays empty.
        assertThat(JkManager.truncateVisible("", 1)).isEmpty();
        assertThat(JkManager.truncateVisible("a", 1)).isEqualTo("a");
        assertThat(TestAnsi.strip(JkManager.truncateVisible("ab", 1))).isEqualTo("…");
    }
}
