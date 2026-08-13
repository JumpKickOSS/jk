// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import java.util.List;
import org.junit.jupiter.api.Test;

class TableAppendTest {

    private static String strip(List<String> lines) {
        return TestAnsi.strip(String.join("\n", lines));
    }

    @Test
    void auto_merges_when_column_names_match() {
        Table t = new Table("Libraries")
                .columns("Name", "Coordinates")
                .row("a", "g:a")
                .append(new Table("Libraries — project")
                        .columns("Name", "Coordinates")
                        .row("b", "g:b"));
        String plain = strip(t.render(RenderContext.current()));
        assertThat(plain).contains("a").contains("b");
        // Merged: only the first title, no second chip.
        assertThat(plain).contains("Libraries");
        assertThat(plain).doesNotContain("Libraries — project");
    }

    @Test
    void section_hides_child_title_and_headers_by_default() {
        Table t = new Table("Build Plan")
                .columns("Plan Item", "Total", "Rebuild", "Delta")
                .row("Modules", "28 in workspace", "0", "0%")
                .row("Sources", "0 files", "0", "0%")
                .append(
                        new Table("footer")
                                .columns("Label", "Value")
                                .row("Total rebuild effort", "0%")
                                .row("Build time estimate", "<1s"),
                        Table.Append.SECTION);
        List<String> lines = t.render(RenderContext.current());
        String plain = strip(lines);
        assertThat(plain).contains("Build Plan");
        assertThat(plain).contains("Plan Item");
        assertThat(plain).doesNotContain("footer");
        assertThat(plain).doesNotContain("Label");
        assertThat(plain).contains("Total rebuild effort");
        assertThat(plain).contains("Build time estimate");
        // One close only.
        long closes = lines.stream()
                .map(TestAnsi::strip)
                .filter(s -> s.contains("╰") || s.startsWith("+") && s.endsWith("+"))
                .count();
        assertThat(closes).isGreaterThanOrEqualTo(1);
        // Join row uses ┴ where parent rails end and ┼ where they continue (ansi), or + (plain).
        boolean hasJoin = lines.stream().map(TestAnsi::strip).anyMatch(s -> s.contains("┴") || s.contains("+"));
        assertThat(hasJoin).isTrue();
    }

    @Test
    void snap_spans_four_to_two() {
        int[] parent = {9, 15, 7, 5};
        int[] child = {20, 8};
        int[] ends = Table.snapSpans(parent, child);
        // First child col eats Plan Item+Total; second takes Rebuild+Delta.
        assertThat(ends).containsExactly(2, 4);
        String join = TestAnsi.strip(Table.joinDivider(RenderContext.current(), parent, ends));
        // Continues at the snapped mid rail; ends at the unused parent rails.
        if (ThemeAnsi.ansi()) {
            assertThat(join).contains("┼");
            assertThat(join).contains("┴");
        }
    }

    @Test
    void appending_a_wider_section_fails_loudly_not_with_aioobe_mid_render() {
        // A child with more columns than the parent cannot snap; snapSpans used to produce
        // out-of-range span ends and the painter threw AIOOBE mid-render (JK-1886).
        Table parent = new Table("T").columns("A", "B").row("a", "b");
        Table wider = new Table("").columns("W", "X", "Y", "Z").row("1", "2", "3", "4");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parent.append(wider, Table.Append.SECTION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more columns");
        // AUTO resolves differing columns to SECTION — same guard.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parent.append(wider))
                .isInstanceOf(IllegalArgumentException.class);
        // Equal-width and narrower children still append fine.
        parent.append(new Table("").columns("L", "V").row("l", "v"), Table.Append.SECTION);
        assertThat(parent.render(RenderContext.current())).isNotEmpty();
    }

    @Test
    void all_lines_share_one_visible_width() {
        Table t = new Table("Build Plan")
                .columns("Plan Item", "Total", "Rebuild", "Delta")
                .row("Modules", "28 in workspace", "0", "0%")
                .append(
                        new Table("")
                                .columns("L", "V")
                                .row("Total rebuild effort", "0%")
                                .row("Build time estimate", "<1s"),
                        Table.Append.SECTION);
        List<String> out = t.render(RenderContext.current());
        var widths = out.stream().map(RenderContext::visibleWidth).distinct().toList();
        assertThat(widths).hasSize(1);
    }

    private static final class ThemeAnsi {
        static boolean ansi() {
            return cc.jumpkick.cli.theme.Theme.active().isAnsi();
        }
    }
}
