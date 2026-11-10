// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.form;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.LiveRegionScreen;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.config.NerdFontCaps;
import cc.jumpkick.terminal.MemoryTerminal;
import cc.jumpkick.terminal.Terminals;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The one-row yes/no the update offer uses: a lead, then the toggle on the same row. */
class InlineYesNoTest {

    private static final String LEAD = "Update Now?";

    private record Answer(Optional<Boolean> value, String written) {}

    private static Answer raw(String keys, Duration typeahead) throws Exception {
        return NoAnsi.forcedAnsi(() -> {
            var bytes = new ByteArrayOutputStream();
            try (MemoryTerminal tty = Terminals.memory(
                            new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)),
                            new ByteArrayOutputStream());
                    var out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
                var ctx = new RenderContext(Theme.active(), true, NerdFontCaps.NONE, 80, 0);
                Optional<Boolean> answer =
                        InlineYesNo.ask(tty, out, new BufferedReader(new StringReader("")), ctx, LEAD, true, typeahead);
                var screen = new LiveRegionScreen(24, 80);
                screen.write(bytes.toString(StandardCharsets.UTF_8));
                return new Answer(answer, String.join("\n", screen.viewport()));
            }
        });
    }

    private static Answer cooked(String lines) throws Exception {
        return NoAnsi.forced(() -> {
            var bytes = new ByteArrayOutputStream();
            try (MemoryTerminal tty =
                            Terminals.memory(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
                    var out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
                var ctx = new RenderContext(Theme.active(), false, NerdFontCaps.NONE, 80, 0);
                Optional<Boolean> answer =
                        InlineYesNo.ask(tty, out, new BufferedReader(new StringReader(lines)), ctx, LEAD, true);
                return new Answer(answer, bytes.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "\n"));
            }
        });
    }

    @Test
    void enter_takes_yes_and_the_row_settles_with_the_answer() throws Exception {
        Answer a = raw(Scripted.ENTER, Duration.ZERO);
        assertThat(a.value()).contains(true);
        assertThat(a.written()).contains(LEAD + " ➜ Yes");
    }

    @Test
    void the_toggle_sits_on_the_lead_row_and_the_arrows_move_it() throws Exception {
        Answer a = raw(Scripted.RIGHT + Scripted.ENTER, Duration.ZERO);
        assertThat(a.value()).contains(false);
        assertThat(a.written()).contains(LEAD + " ➜ No").doesNotContain("Yes");
    }

    @Test
    void y_and_n_answer_at_once_and_escape_backs_out() throws Exception {
        assertThat(raw("n", Duration.ZERO).value()).contains(false);
        assertThat(raw("y", Duration.ZERO).value()).contains(true);
        assertThat(raw("\u001b", Duration.ZERO).value()).isEmpty();
        assertThat(raw(Scripted.CTRL_C, Duration.ZERO).value()).isEmpty();
    }

    @Test
    void the_live_row_shows_both_answers_after_the_lead() {
        var ctx = new RenderContext(Theme.active(), true, NerdFontCaps.NONE, 80, 0);
        String row = InlineYesNo.row(ctx, LEAD, true).replaceAll("\u001b\\[[0-9;]*m", "");
        assertThat(row).startsWith(LEAD + " ").contains("Yes").contains("No");
        assertThat(row.indexOf("Yes")).isLessThan(row.lastIndexOf("No"));
    }

    @Test
    void plain_mode_is_a_cooked_y_n_line() throws Exception {
        Answer yes = cooked("\n");
        assertThat(yes.value()).contains(true);
        assertThat(yes.written()).startsWith(LEAD + " [Y/n]: ").contains("-> Yes");

        assertThat(cooked("n\n").value()).contains(false);
        assertThat(cooked("maybe\nno\n").written()).contains("Answer y or n.");
        assertThat(cooked("").value()).isEmpty();
    }
}
