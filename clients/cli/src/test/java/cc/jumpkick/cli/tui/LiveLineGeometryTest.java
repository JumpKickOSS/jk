// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.testing.NoAnsi;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.NerdFontCaps;
import org.junit.jupiter.api.Test;

/**
 * Geometry of a line that gets repainted in place.
 *
 * <p>{@code jk jdk install lts} printed a screenful of stacked half-frames instead of one moving
 * bar. The cause was arithmetic, not animation: the wedge plus a 40-cell bar plus
 * {@code NN% · Downloading …} measured 90–92 columns, an 80-column terminal wrapped it, and
 * {@code \r} rewinds only the current <em>physical</em> row — so every frame landed on a new line.
 *
 * <p>These pin the two properties a repainted row must have (fits the row budget, contains no
 * newline) and the two that were regressions in their own right: the cancel line reusing the build's
 * chrome rather than a bar look found nowhere else, and the bar width being a real parameter.
 */
class LiveLineGeometryTest {

    private static final String LONG_LABEL = "· Downloading Eclipse Temurin 26.0.2.1 (aarch64, tar.gz)";

    private static RenderContext ctx(int columns, NerdFontCaps caps) {
        return new RenderContext(Theme.active(), true, caps, columns, 0);
    }

    private static JkWedge downloadWedge(int percent, int segments) {
        return new JkWedge(Icon.spinner(), "JDK", RichText.empty())
                .variant(JkWedge.Variant.WORK)
                .progress(new Progress(percent, 100).segments(segments).suffix(RichText.plain(LONG_LABEL)));
    }

    /** Block-bar cells: the full block plus the seven fractional eighths. */
    private static int barCells(String rendered) {
        int n = 0;
        for (int i = 0; i < rendered.length(); i++) {
            char c = rendered.charAt(i);
            if (c == '█' || (c >= '▉' && c <= '▏')) n++;
        }
        return n;
    }

    @Test
    void a_live_line_fits_the_row_budget_at_every_percent() throws Exception {
        NoAnsi.forcedAnsi(() -> {
            for (int columns : new int[] {80, 100, 40}) {
                int budget = RenderContext.rowColumnBudget(columns);
                for (NerdFontCaps caps : new NerdFontCaps[] {NerdFontCaps.NONE, NerdFontCaps.ALL}) {
                    for (int pct : new int[] {0, 1, 5, 37, 64, 99, 100}) {
                        String line =
                                downloadWedge(pct, Progress.NARROW_SEGMENTS).renderLiveLine(ctx(columns, caps));
                        assertThat(RenderContext.visibleWidth(line))
                                .as("cols=%d caps=%s pct=%d must not wrap", columns, caps, pct)
                                .isLessThanOrEqualTo(budget);
                    }
                }
            }
            return null;
        });
    }

    @Test
    void a_live_line_never_contains_a_newline() throws Exception {
        // A frame carrying a newline breaks \r repaint exactly as a wrap does, and would stack the
        // same way — so it is worth pinning separately from the width.
        NoAnsi.forcedAnsi(() -> {
            for (int pct : new int[] {0, 50, 100}) {
                for (NerdFontCaps caps : new NerdFontCaps[] {NerdFontCaps.NONE, NerdFontCaps.ALL}) {
                    assertThat(downloadWedge(pct, Progress.NARROW_SEGMENTS).renderLiveLine(ctx(80, caps)))
                            .doesNotContain("\n")
                            .doesNotContain("\r");
                }
            }
            return null;
        });
    }

    @Test
    void the_bar_starts_flush_against_the_badge_without_a_nerd_font() throws Exception {
        // The plain chip carries its own two-space pill trail; the generic separator made three,
        // and the bar read as detached from its badge in every terminal without the PUA glyphs.
        NoAnsi.forcedAnsi(() -> {
            String line = downloadWedge(37, Progress.NARROW_SEGMENTS).renderLiveLine(ctx(120, NerdFontCaps.NONE));
            String bare = TestAnsi.strip(line);
            int firstCell = bare.indexOf('█');
            assertThat(firstCell).as("the bar should be present at 37%%").isGreaterThan(0);
            String head = bare.substring(0, firstCell);
            assertThat(head).endsWith("  ");
            assertThat(head).doesNotEndWith("   ");
            return null;
        });
    }

    @Test
    void the_bar_width_is_a_parameter_forty_by_default_and_thirty_two_when_narrowed() throws Exception {
        NoAnsi.forcedAnsi(() -> {
            assertThat(Progress.DEFAULT_SEGMENTS).isEqualTo(40);
            assertThat(Progress.NARROW_SEGMENTS).isEqualTo(32);
            // At 100% every cell is filled, so the cell count IS the width.
            assertThat(barCells(
                            downloadWedge(100, Progress.DEFAULT_SEGMENTS).renderLiveLine(ctx(200, NerdFontCaps.ALL))))
                    .isEqualTo(Progress.DEFAULT_SEGMENTS);
            assertThat(barCells(
                            downloadWedge(100, Progress.NARROW_SEGMENTS).renderLiveLine(ctx(200, NerdFontCaps.ALL))))
                    .isEqualTo(Progress.NARROW_SEGMENTS);
            assertThat(new Progress(1, 2).narrow().segments()).isEqualTo(Progress.NARROW_SEGMENTS);
            return null;
        });
    }

    @Test
    void a_cancelled_download_reuses_the_build_chrome_and_no_track_glyph() throws Exception {
        // The cancel used to dump SEGMENTS bare ▰ — a third bar look, no wedge, no erase, so the
        // previous frame's trailing `%` survived underneath. It now settles exactly like a
        // cancelled build, differing only in the subject.
        NoAnsi.forcedAnsi(() -> {
            String jdk =
                    JkWedge.cancelled("JDK", "JDK download", true, "took 4.0s").renderLine(ctx(80, NerdFontCaps.ALL));
            String build = JkWedge.cancelled("Build", true, "took 398ms").renderLine(ctx(80, NerdFontCaps.ALL));

            assertThat(TestAnsi.strip(jdk)).contains("JDK download was cancelled by user");
            assertThat(TestAnsi.strip(build)).contains("job was cancelled by user");
            assertThat(jdk)
                    .as("no skinny track glyphs on a cancel")
                    .doesNotContain(String.valueOf(ProgressBar.FILLED_CHAR))
                    .doesNotContain(String.valueOf(ProgressBar.EMPTY_CHAR));
            // Same chrome: identical once the differing subject is removed.
            assertThat(jdk.replace("JDK download was", "job was")
                            .replace(" JDK ", " Build ")
                            .replace("took 4.0s", "took 398ms"))
                    .isEqualTo(build);
            return null;
        });
    }
}
