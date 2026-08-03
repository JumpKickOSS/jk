// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** JK-1375: the shared list-command table renderer. */
class BoxTableRenderTest {

    private static String stripAnsi(String s) {
        return s.replaceAll("\\[[0-9;]*m", "");
    }

    @Test
    void renders_title_header_rows_and_close() {
        List<String> out = BoxTable.render(
                "Things", List.of("Name", "Value"), List.of(List.of("alpha", "1"), List.of("b", "22")));
        // title + top divider + header + divider + 2 rows + close
        assertThat(out).hasSize(7);
        String plain = stripAnsi(String.join("\n", out));
        assertThat(plain).contains("Things");
        assertThat(plain).contains("Name");
        assertThat(plain).contains("alpha");
        assertThat(plain).contains("22");
    }

    @Test
    void pads_short_rows_and_truncates_long_ones() {
        List<String> out =
                BoxTable.render("T", List.of("A", "B"), List.of(List.of("only-a"), List.of("x", "y", "ignored")));
        String plain = stripAnsi(String.join("\n", out));
        assertThat(plain).contains("only-a");
        assertThat(plain).doesNotContain("ignored");
    }

    @Test
    void body_lines_share_one_visible_width() {
        List<String> out = BoxTable.render("T", List.of("A"), List.of(List.of("wide-cell-content"), List.of("x")));
        var widths = out.stream()
                .skip(1) // title bar has its own chrome accounting
                .map(BoxTableRenderTest::stripAnsi)
                .map(String::length)
                .distinct()
                .toList();
        assertThat(widths).hasSize(1);
    }

    @Test
    void hybrid_settle_lines_carry_command_and_message() {
        // Settle contract for wave-2 hybrids (tool install / auth logout / history rm):
        // one wedge line naming the command and the outcome.
        String ok = stripAnsi(CommandWedge.ok("Tool", "Installed foo -> /bin/foo"));
        assertThat(ok).contains("Tool");
        assertThat(ok).contains("Installed foo");
        String fail = stripAnsi(CommandWedge.fail("Verify", "artifact mismatch"));
        assertThat(fail).contains("Verify");
        assertThat(fail).contains("artifact mismatch");
    }
}
