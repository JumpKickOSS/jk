// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.terminal.Ansi;
import org.junit.jupiter.api.Test;

/**
 * The raw-mode settle rewinds over the printed hint to paint the chosen answer in its place. The
 * hint is SGR-colored (the settle path only runs when ANSI is active), so the rewind must count
 * visible columns — rewinding by raw {@code String.length()} overshoots by the escape bytes,
 * lands the cursor inside the question text, and the erase wipes the line.
 */
class PromptSettleTest {

    private static final String DIM = "\u001b[38;5;240m";
    private static final String RESET = "\u001b[0m";

    @Test
    void settle_rewinds_by_visible_hint_width_not_raw_length() {
        // Visible text is "[y/N]" — five columns — but the raw string is far longer.
        String hint = DIM + "[" + RESET + "y" + DIM + "/" + RESET + "N" + DIM + "]" + RESET;
        assertThat(hint.length()).isGreaterThan(20);

        String settled = Prompt.settleOverwrite(hint, "Yes");

        assertThat(settled).startsWith(Ansi.cursorBack(5 + 1));
        assertThat(settled).endsWith("Yes" + Ansi.ERASE_LINE_TO_END + "\r\n");
    }

    @Test
    void plain_hint_width_matches_raw_length() {
        String settled = Prompt.settleOverwrite("[y/N]", "No");
        assertThat(settled).startsWith(Ansi.cursorBack(6));
    }
}
