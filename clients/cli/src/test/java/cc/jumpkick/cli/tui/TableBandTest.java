// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.NerdFontCaps;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * JK-1877: an emphasized (banded) row must paint its band background across the whole interior —
 * including the alignment fill of cells shorter than their column. Padding with bare spaces shows
 * terminal-background stripes inside the band (`jk jdk list` active row).
 */
class TableBandTest {

    @Test
    void banded_row_alignment_fill_carries_the_band_background() {
        if (!Theme.active().isAnsi()) return; // ANSI suppressed (CI/dumb term): band never paints
        Table t = new Table("T").columns(new Table.Column("A"), new Table.Column("B"));
        t.row(Table.Row.data(RichText.plain("x"), RichText.plain("bb")).emphasized());
        t.row(Table.Row.data(RichText.plain("wide-cell"), RichText.plain("b")));
        List<String> out = t.render(new RenderContext(Theme.active(), true, NerdFontCaps.NONE, 80, 0));

        String banded = out.stream().filter(l -> l.contains("x")).findFirst().orElseThrow();
        // The "x" cell is 8 columns short of "wide-cell"; its fill must be styled spaces, never a
        // bare multi-space run that drops the band.
        assertThat(banded).doesNotContainPattern(Pattern.compile("x {2}"));
        // Fill spaces carry an SGR background: an escape sequence sits between x and the fill.
        assertThat(banded).containsPattern(Pattern.compile("x\u001b\\["));

        // Non-banded rows keep plain fill (no per-space styling explosion).
        String plainRow = out.stream().filter(l -> l.contains("b ")).findFirst().orElseThrow();
        assertThat(plainRow).isNotEmpty();
    }

    @Test
    void centered_column_centers_its_header_too() {
        // JK-1888: headers sit over their data; a CENTER column's header must not stick left.
        Table t = new Table("T").columns(new Table.Column("V", Table.Align.CENTER), new Table.Column("Name"));
        t.row(Table.Row.data(RichText.plain("2525"), RichText.plain("temurin-wide-cell")));
        List<String> out = t.render(new RenderContext(Theme.active(), false, NerdFontCaps.NONE, 80, 0));
        String header = out.stream().filter(l -> l.contains("V")).findFirst().orElseThrow();
        // Column width is 4 ("2525"); centered "V" gets left fill: "|  V   |" not "| V    |".
        assertThat(header).contains("|  V  ");
    }

    @Test
    void plain_context_headers_carry_no_sgr_even_under_an_ansi_global_theme() {
        // JK-1889: headerCell must follow the render context like every other paint decision.
        Table t = new Table("T").columns("Name");
        t.row(Table.Row.data(RichText.plain("alpha")));
        List<String> out = t.render(new RenderContext(Theme.active(), false, NerdFontCaps.NONE, 80, 0));
        for (String line : out) {
            assertThat(line).doesNotContain("\u001b[");
        }
    }
}
