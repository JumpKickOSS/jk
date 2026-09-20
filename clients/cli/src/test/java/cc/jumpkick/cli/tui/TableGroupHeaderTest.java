// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.NerdFontCaps;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A full-span row between data rows is a group header: rails collapse above it and reopen below. */
class TableGroupHeaderTest {

    private static Table grouped() {
        Table t = new Table("T").columns("Name", "Value");
        t.row(Table.Row.span(Table.Cell.of("group one").span(2)));
        t.row("alpha", "1");
        t.row("beta", "2");
        t.row(Table.Row.span(Table.Cell.of("group two").span(2)));
        t.row("gamma", "3");
        return t;
    }

    @Test
    void headers_collapse_and_reopen_the_rails() {
        List<String> out = grouped().render(new RenderContext(Theme.active(), true, NerdFontCaps.NONE, 80, 0));
        List<String> plain = out.stream().map(TestAnsi::strip).toList();
        int one = indexOf(plain, "group one");
        int two = indexOf(plain, "group two");
        // Right under the column headers the divider collapses the rails: no second divider.
        assertThat(plain.get(one - 1)).startsWith("├").contains("┴").doesNotContain("┼");
        assertThat(plain.get(one - 2)).contains("Name");
        // The first data row after a header reopens them.
        assertThat(plain.get(one + 1)).startsWith("├").contains("┬");
        assertThat(plain.get(one + 2)).contains("alpha");
        // Between groups: collapse, header, reopen.
        assertThat(plain.get(two - 1)).contains("┴").doesNotContain("┼");
        assertThat(plain.get(two + 1)).contains("┬");
        assertThat(plain.get(two + 2)).contains("gamma");
        // The last visible row is data, so the close carries the rails.
        assertThat(plain.getLast()).startsWith("╰").contains("┴");
        assertThat(plain.stream().map(RenderContext::visibleWidth).distinct()).hasSize(1);
    }

    @Test
    void plain_mode_keeps_one_width() {
        List<String> out = grouped().render(new RenderContext(Theme.active(), false, NerdFontCaps.NONE, 80, 0));
        assertThat(out.stream().map(RenderContext::visibleWidth).distinct()).hasSize(1);
        assertThat(String.join("\n", out)).contains("group one").contains("gamma");
    }

    private static int indexOf(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) return i;
        }
        throw new AssertionError("missing " + needle);
    }
}
