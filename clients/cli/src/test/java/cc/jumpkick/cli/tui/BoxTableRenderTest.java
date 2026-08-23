// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.terminal.Style;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** the shared list-command table renderer. */
class BoxTableRenderTest {

    @Test
    void renders_title_header_rows_and_close() {
        List<String> out =
                Table.render("Things", List.of("Name", "Value"), List.of(List.of("alpha", "1"), List.of("b", "22")));
        // title + top divider + header + divider + 2 rows + close
        assertThat(out).hasSize(7);
        String plain = TestAnsi.strip(String.join("\n", out));
        assertThat(plain).contains("Things");
        assertThat(plain).contains("Name");
        assertThat(plain).contains("alpha");
        assertThat(plain).contains("22");
    }

    @Test
    void header_cells_are_italic_when_ansi() {
        if (!cc.jumpkick.cli.theme.Theme.active().isAnsi()) return;
        String cell = Table.headerCell("Name");
        assertThat(cell).isEqualTo(cc.jumpkick.cli.theme.Theme.colorize("Name", Style.EMPTY.italic()));
        // Full table: header row (index 2) carries italic; body does not restyle plain cells.
        List<String> out = Table.render("T", List.of("Name"), List.of(List.of("alpha")));
        assertThat(out.get(2)).contains(Table.headerCell("Name"));
        assertThat(out.get(4)).contains("alpha");
        assertThat(out.get(4)).doesNotContain(Table.headerCell("alpha"));
    }

    @Test
    void pads_short_rows_and_truncates_long_ones() {
        List<String> out =
                Table.render("T", List.of("A", "B"), List.of(List.of("only-a"), List.of("x", "y", "ignored")));
        String plain = TestAnsi.strip(String.join("\n", out));
        assertThat(plain).contains("only-a");
        assertThat(plain).doesNotContain("ignored");
    }

    /** Visible terminal columns of a line once ANSI chrome is stripped (wcwidth-aware). */
    private static int visibleColumns(String s) {
        return cc.jumpkick.terminal.Width.columns(TestAnsi.strip(s));
    }

    private static void assertUniformWidth(List<String> out) {
        // Title bar included:  widens the table when the chip would overhang.
        var widths =
                out.stream().map(BoxTableRenderTest::visibleColumns).distinct().toList();
        assertThat(widths).hasSize(1);
    }

    @Test
    void body_lines_share_one_visible_width() {
        List<String> out = Table.render("T", List.of("A"), List.of(List.of("wide-cell-content"), List.of("x")));
        assertUniformWidth(out);
    }

    @Test
    void plain_mode_ellipsis_cells_stay_aligned() throws Exception {
        // PlainAscii expands … → ... at the print boundary; render must account for the
        // expanded width up front so rows with truncated cells keep the rails aligned.
        withNoAnsi(() -> {
            List<String> out = Table.render(
                    "Build history",
                    List.of("Id", "Project"),
                    List.of(List.of("1", "very-long-project…"), List.of("2", "ok")));
            assertUniformWidth(out);
            assertThat(String.join("\n", out)).contains("very-long-project...");
            return null;
        });
    }

    @Test
    void cjk_cells_stay_aligned_under_wcwidth() {
        // CJK chars are 1 UTF-16 unit but 2 terminal columns; width accounting must be
        // column-aware or the row overflows its rails.
        List<String> out =
                Table.render("T", List.of("Project", "Took"), List.of(List.of("构建工具", "1s"), List.of("app", "2s")));
        assertUniformWidth(out);
    }

    @Test
    void long_title_widens_table_instead_of_overhanging() {
        List<String> out = Table.render(
                "Tasks — some:very-long-module-name (deep/relative/path)", List.of("A"), List.of(List.of("x")));
        assertUniformWidth(out);
    }

    @Test
    void zero_rows_render_without_stray_divider() {
        List<String> out = Table.render("Empty", List.of("A", "B"), List.of());
        // title + top divider + header + close — no ├┼┤ between header and bottom border
        assertThat(out).hasSize(4);
        assertThat(TestAnsi.strip(String.join("\n", out))).doesNotContain("┼");
        assertUniformWidth(out);
    }

    @Test
    void no_ansi_output_is_pure_ascii_for_history_tasks_library_glyphs() throws Exception {
        // ⊛ (history cancelled), — (Tasks/Library-search titles + n/a durations), … (truncation)
        // must all be rewritten before the "ASCII-only" plain output leaves the renderer.
        withNoAnsi(() -> {
            List<String> out = Table.render(
                    "Tasks — g:n (path)",
                    List.of("", "Id", "Took"),
                    List.of(List.of("⊛", "42", "—"), List.of("✓", "43", "1.2s…")));
            String joined = String.join("\n", out);
            assertThat(joined.chars().allMatch(c -> c < 0x80))
                    .as("plain output must be pure ASCII: %s", joined)
                    .isTrue();
            assertUniformWidth(out);
            return null;
        });
    }

    private static <T> T withNoAnsi(Supplier<T> body) throws Exception {
        cc.jumpkick.config.JkConfig noAnsi = cc.jumpkick.config.JkConfig.empty().withNoAnsi(Optional.of(true));
        cc.jumpkick.config.Session original = cc.jumpkick.config.SessionContext.current();
        try {
            return cc.jumpkick.config.SessionContext.where(original.withConfig(noAnsi), body::get);
        } finally {
            cc.jumpkick.config.SessionContext.install(original);
        }
    }

    @Test
    void hybrid_settle_lines_carry_command_and_message() {
        // Settle contract for wave-2 hybrids (tool install / auth logout / history rm):
        // one wedge line naming the command and the outcome.
        String ok = TestAnsi.strip(CommandWedge.ok("Tool", "Installed foo -> /bin/foo"));
        assertThat(ok).contains("Tool");
        assertThat(ok).contains("Installed foo");
        String fail = TestAnsi.strip(CommandWedge.fail("Verify", "artifact mismatch"));
        assertThat(fail).contains("Verify");
        assertThat(fail).contains("artifact mismatch");
    }
}
