// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** OSC handling in {@link RenderContext}'s width / truncation scanners. */
class RenderContextOscTest {

    private static final String ESC = "\u001b";
    private static final String BEL = "\u0007";
    private static final String OSC_LINK_OPEN = ESC + "]8;;https://example.com/very/long/url" + BEL;
    private static final String OSC_LINK_CLOSE = ESC + "]8;;" + BEL;

    @Test
    void unterminated_osc_measures_as_zero_width() {
        String truncatedUpstream = "before " + ESC + "]8;;https://example.com/clipped";
        assertThat(RenderContext.visibleWidth(truncatedUpstream)).isEqualTo("before ".length());
        assertThat(RenderContext.stripAnsi(truncatedUpstream)).isEqualTo("before ");
    }

    @Test
    void skip_escape_handles_csi_osc_and_two_char_forms() {
        String csi = ESC + "[31mX";
        assertThat(RenderContext.skipEscape(csi, 0)).isEqualTo(5);
        String oscBel = OSC_LINK_CLOSE + "X";
        assertThat(RenderContext.skipEscape(oscBel, 0)).isEqualTo(OSC_LINK_CLOSE.length());
        String oscSt = ESC + "]8;;" + ESC + "\\X";
        assertThat(RenderContext.skipEscape(oscSt, 0)).isEqualTo(7);
        String unterminated = ESC + "]8;;clip";
        assertThat(RenderContext.skipEscape(unterminated, 0)).isEqualTo(unterminated.length());
        assertThat(RenderContext.skipEscape(ESC + "(BX", 0)).isEqualTo(2);
    }

    @Test
    void truncate_visible_never_counts_a_link_payload_as_columns() {
        String linked = OSC_LINK_OPEN + "label" + OSC_LINK_CLOSE + " tail";
        // Budget fits the whole visible text - everything (links included) passes through.
        assertThat(RenderContext.truncateVisible(linked, 40)).isEqualTo(linked);
    }

    @Test
    void truncate_visible_never_emits_an_unterminated_osc() {
        String linked = OSC_LINK_OPEN + "label" + OSC_LINK_CLOSE + " a very long tail that overflows";
        String cut = RenderContext.truncateVisible(linked, 12);
        assertThat(cut).contains("label");
        long openers = 0;
        int idx = 0;
        int unterminated = 0;
        while ((idx = cut.indexOf(ESC + "]", idx)) >= 0) {
            openers++;
            int end = RenderContext.skipEscape(cut, idx);
            boolean terminated = end > idx + 1
                    && (cut.charAt(end - 1) == BEL.charAt(0)
                            || (end - 2 > idx && cut.charAt(end - 2) == ESC.charAt(0) && cut.charAt(end - 1) == '\\'));
            if (!terminated) unterminated++;
            idx = end;
        }
        assertThat(unterminated).isZero();
        assertThat(openers).isGreaterThan(0); // the complete link sequences survived verbatim
    }

    @Test
    void truncate_inside_a_link_emits_a_synthetic_close_before_the_ellipsis() {
        // the cut lands inside the linked label, so the input's own close is dropped —
        // without a synthetic close the ellipsis, EL, and every later row join the hyperlink.
        String linked = "go " + OSC_LINK_OPEN + "clickable label text" + OSC_LINK_CLOSE + " tail";
        String cut = RenderContext.truncateVisible(linked, 8);
        int open = cut.indexOf(OSC_LINK_OPEN);
        assertThat(open).isNotNegative();
        int close = cut.indexOf(OSC_LINK_CLOSE, open + OSC_LINK_OPEN.length());
        assertThat(close).as("synthetic close after the open in %s", cut).isGreaterThan(open);
        assertThat(cut.indexOf(Glyphs.ELLIPSIS))
                .as("ellipsis is outside the link")
                .isGreaterThan(close);
    }

    @Test
    void truncate_never_splits_a_surrogate_pair() {
        // astral chars (JUnit display names can carry emoji) must be cut whole — a lone
        // high surrogate before the ellipsis is mojibake.
        String emoji = "😀"; // 😀 (width 2)
        String s = emoji.repeat(6);
        for (int cols = 1; cols <= 12; cols++) {
            String cut = RenderContext.truncateVisible(s, cols);
            for (int i = 0; i < cut.length(); i++) {
                if (Character.isHighSurrogate(cut.charAt(i))) {
                    assertThat(i + 1).as("pair complete in %s @%d", cut, i).isLessThan(cut.length());
                    assertThat(Character.isLowSurrogate(cut.charAt(i + 1))).isTrue();
                }
            }
        }
    }

    @Test
    void truncate_counts_wide_glyphs_as_two_columns() {
        // the paint path and the reflow estimator share one width metric (wcwidth).
        String cjk = "漢漢漢"; // 3 glyphs, 6 columns
        assertThat(RenderContext.truncateVisible(cjk, 6)).isEqualTo(cjk);
        String cut = RenderContext.truncateVisible(cjk, 4);
        assertThat(cut).startsWith("漢").contains(Glyphs.ELLIPSIS);
        assertThat(RenderContext.visibleWidth(cut)).isLessThanOrEqualTo(4);
        // Budget 5: two glyphs (4 cols) + ellipsis (1) fits exactly.
        assertThat(RenderContext.visibleWidth(RenderContext.truncateVisible(cjk, 5)))
                .isLessThanOrEqualTo(5);
    }

    @Test
    void truncate_with_the_link_already_closed_adds_no_extra_close() {
        String linked = OSC_LINK_OPEN + "ok" + OSC_LINK_CLOSE + " a very long tail that overflows";
        String cut = RenderContext.truncateVisible(linked, 12);
        int count = 0;
        int idx = 0;
        while ((idx = cut.indexOf(OSC_LINK_CLOSE, idx)) >= 0) {
            count++;
            idx += OSC_LINK_CLOSE.length();
        }
        assertThat(count).isEqualTo(1);
    }

    @Test
    void non_sgr_csi_is_dropped_but_sgr_passes() {
        // JK-2109: cursor motion / erase CSI from raw tool output must never reach the live
        // region; SGR coloring stays.
        String in = "\u001b[1Aup\u001b[2K \u001b[31mred\u001b[0m";
        String out = RenderContext.truncateVisible(in, 40);
        assertThat(out).doesNotContain("\u001b[1A");
        assertThat(out).doesNotContain("\u001b[2K");
        assertThat(out).contains("\u001b[31mred\u001b[0m");
        assertThat(out).contains("up");
    }
}
