// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.stream.LongStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class RegionPainterTest {

    private static final int ROWS = 24;
    private static final int COLS = 60;

    @Test
    void anUnchangedFrameWritesNothing() {
        RegionPainter p = new RegionPainter(RegionPainter.Park.BELOW);
        List<String> frame = List.of(pulse(0) + " Build " + bar(40), "  ├─ compile", "  ╰─ test");
        p.paint(new StringBuilder(), frame, COLS, 0, ROWS - 1);
        StringBuilder again = new StringBuilder();
        p.paint(again, frame, COLS, 0, ROWS - 1);
        assertThat(again).isEmpty();
    }

    @Test
    void aPulseOnlyFrameWritesTheOneCell() {
        RegionPainter p = new RegionPainter(RegionPainter.Park.BELOW);
        List<String> rows = new ArrayList<>(List.of(pulse(0) + " Build " + bar(40)));
        for (int i = 0; i < 9; i++) rows.add("  ├─ module-" + i + " › Compile");
        VtScreen vt = new VtScreen(ROWS, COLS + 40);
        StringBuilder first = new StringBuilder();
        p.paint(first, rows, COLS + 40, 0, ROWS - 1);
        vt.write(first);
        rows.set(0, pulse(7) + " Build " + bar(40));
        StringBuilder f = new StringBuilder();
        p.paint(f, rows, COLS + 40, 0, ROWS - 1);
        vt.write(f);
        assertThat(RegionPainter.bytes(f)).isLessThanOrEqualTo(40);
        assertThat(vt.text(vt.row - 10)).startsWith("● Build ");
        assertThat(vt.row(vt.row - 10)[0].pen().fg()).isEqualTo("38;2;7;7;200");
    }

    @Test
    void aWholeChangedRowIsWrittenWholeNotJumpedThrough() {
        RegionPainter p = new RegionPainter(RegionPainter.Park.IN_ROW);
        StringBuilder a = new StringBuilder();
        p.paint(a, List.of("abcdefghij"), COLS, 0, 0);
        StringBuilder b = new StringBuilder();
        p.paint(b, List.of("aXcXeXgXiX"), COLS, 0, 0);
        // Five one-cell spans: rewriting the run from the first change beats five jumps.
        assertThat(b.toString()).isEqualTo("\r" + "aXcXeXgXiX");
    }

    @Test
    void aShorterRowErasesItsOldTail() {
        RegionPainter p = new RegionPainter(RegionPainter.Park.IN_ROW);
        VtScreen vt = new VtScreen(ROWS, COLS);
        StringBuilder a = new StringBuilder();
        p.paint(a, List.of("Resolving 12 of 40 artifacts"), COLS, 0, 0);
        vt.write(a);
        StringBuilder b = new StringBuilder();
        p.paint(b, List.of("Resolving 13 of 40"), COLS, 0, 0);
        vt.write(b);
        assertThat(vt.text(vt.row)).isEqualTo("Resolving 13 of 40");
    }

    @Test
    void statusIsSentOnlyWhenItChanges() {
        RegionPainter p = new RegionPainter(RegionPainter.Park.BELOW);
        StringBuilder f = new StringBuilder();
        p.status(f, Ansi.taskbarProgress(40));
        p.status(f, Ansi.taskbarProgress(40));
        assertThat(f.toString()).isEqualTo(Ansi.taskbarProgress(40));
        p.status(f, Ansi.taskbarProgress(41));
        assertThat(f.toString()).isEqualTo(Ansi.taskbarProgress(40) + Ansi.taskbarProgress(41));
    }

    @Test
    void rowsAreClippedSoTheyNeverWrap() {
        RegionPainter p = new RegionPainter(RegionPainter.Park.BELOW);
        VtScreen vt = new VtScreen(ROWS, 20);
        StringBuilder f = new StringBuilder();
        p.paint(f, List.of("x".repeat(30), "second"), 20, 0, ROWS - 1);
        vt.write(f);
        assertThat(vt.text(vt.row - 2)).isEqualTo("x".repeat(20));
        assertThat(vt.text(vt.row - 1)).isEqualTo("second");
    }

    /**
     * Random frames, growth, shrink, drift and styles, with and without ONLCR: after every paint
     * the emulated region shows exactly the frame, and nothing is left under it. Seeds are
     * independent, so they run in parallel.
     */
    @Test
    void theScreenAlwaysShowsTheFrame() {
        LongStream.rangeClosed(1, 500).parallel().forEach(seed -> {
            Random rnd = new Random(seed);
            VtScreen vt = new VtScreen(ROWS, COLS);
            vt.onlcr = rnd.nextBoolean();
            for (int i = 0; i < ROWS + 3; i++) vt.write("~ earlier output\r\n");
            RegionPainter p = new RegionPainter(RegionPainter.Park.BELOW);
            List<String> frame = randomFrame(rnd, List.of());
            for (int step = 0; step < 40; step++) {
                int drift = 0;
                if (p.height() > 0 && rnd.nextInt(8) == 0) {
                    drift = 1 + rnd.nextInt(3);
                    for (int d = 0; d < drift; d++) vt.write("child line " + d + "\r\n");
                }
                StringBuilder f = new StringBuilder();
                p.paint(f, frame, COLS, drift, ROWS - 1);
                vt.write(f);
                assertShows(vt, frame, "seed " + seed + " step " + step + " onlcr " + vt.onlcr);
                frame = randomFrame(rnd, frame);
            }
        });
    }

    @Test
    void anInRowRegionAlwaysShowsTheFrame() {
        LongStream.rangeClosed(1, 300).parallel().forEach(seed -> {
            Random rnd = new Random(seed);
            VtScreen vt = new VtScreen(ROWS, COLS);
            vt.write("$ jk outdated\r\n");
            RegionPainter p = new RegionPainter(RegionPainter.Park.IN_ROW);
            int at = vt.row;
            for (int step = 0; step < 40; step++) {
                String line = randomRow(
                        rnd,
                        rnd.nextInt(3) == 0
                                ? null
                                : p.rows().isEmpty() ? null : p.rows().getFirst());
                StringBuilder f = new StringBuilder();
                p.paint(f, List.of(line), COLS, 0, 0);
                vt.write(f);
                assertThat(vt.row).as("seed %d", seed).isEqualTo(at);
                assertRow(vt, at, line, "seed " + seed + " step " + step);
            }
        });
    }

    @Test
    void liftLeavesTheCursorAtTheFormerTop() {
        RegionPainter p = new RegionPainter(RegionPainter.Park.BELOW);
        VtScreen vt = new VtScreen(ROWS, COLS);
        vt.write("above\r\n");
        StringBuilder f = new StringBuilder();
        p.paint(f, List.of("one", "two", "three"), COLS, 0, ROWS - 1);
        p.lift(f, 0, ROWS - 1);
        f.append("scrollback line\r\n");
        p.paint(f, List.of("one", "two", "three"), COLS, 0, ROWS - 1);
        vt.write(f);
        assertThat(vt.text(vt.row - 5)).isEqualTo("above");
        assertThat(vt.text(vt.row - 4)).isEqualTo("scrollback line");
        assertThat(vt.text(vt.row - 3)).isEqualTo("one");
        assertThat(vt.text(vt.row - 1)).isEqualTo("three");
    }

    private static void assertShows(VtScreen vt, List<String> frame, String why) {
        assertThat(vt.col).as(why + ": parked at column 0").isZero();
        int top = vt.row - frame.size();
        for (int i = 0; i < frame.size(); i++) assertRow(vt, top + i, frame.get(i), why + " row " + i);
        for (int r = vt.row; r < vt.rows; r++) {
            String below = vt.text(r);
            if (!below.isEmpty())
                assertThat(below).as(why + ": below the region").isEmpty();
        }
    }

    private static void assertRow(VtScreen vt, int r, String line, String why) {
        VtScreen ref = new VtScreen(1, COLS);
        ref.write(line);
        VtScreen.Shown[] want = ref.row(0);
        VtScreen.Shown[] got = vt.row(r);
        for (int c = 0; c < COLS; c++) {
            // Only a mismatch pays for the description: this runs for every cell of every frame.
            if (Objects.equals(got[c], want[c])) continue;
            assertThat(got[c])
                    .as("%s col %d: %s vs %s", why, c, vt.text(r), ref.text(0))
                    .isEqualTo(want[c]);
        }
    }

    private static final String[] GLYPHS = {"a", "b", "x", " ", "█", "●", "─", "中", "", "✓"};
    private static final String[] PENS = {
        "", "1", "2", "38;2;10;20;30", "38;2;200;10;10", "4;38;2;61;155;255", "48;2;15;71;134", "1;3;9", "7"
    };

    private static List<String> randomFrame(Random rnd, List<String> prev) {
        int height = prev.isEmpty() || rnd.nextInt(5) == 0 ? 1 + rnd.nextInt(8) : prev.size();
        List<String> rows = new ArrayList<>(height);
        for (int i = 0; i < height; i++) {
            String was = i < prev.size() ? prev.get(i) : null;
            rows.add(rnd.nextInt(3) == 0 ? (was == null ? randomRow(rnd, null) : was) : randomRow(rnd, was));
        }
        return rows;
    }

    /** A random row, or {@code was} with a few cells changed so the diff has spans to find. */
    private static String randomRow(Random rnd, @Nullable String was) {
        if (was != null && rnd.nextBoolean()) {
            VtScreen ref = new VtScreen(1, COLS);
            ref.write(was);
            List<String> cells = new ArrayList<>();
            List<String> pens = new ArrayList<>();
            for (VtScreen.Shown s : ref.row(0)) {
                if (s.glyph() == null) continue;
                cells.add(s.glyph());
                pens.add(s.pen().body());
            }
            int len = Math.max(0, ref.text(0).length() + rnd.nextInt(5) - 2);
            int changes = 1 + rnd.nextInt(3);
            for (int k = 0; k < changes && !cells.isEmpty(); k++) {
                int at = rnd.nextInt(cells.size());
                cells.set(at, GLYPHS[rnd.nextInt(GLYPHS.length)]);
                pens.set(at, PENS[rnd.nextInt(PENS.length)]);
            }
            return assemble(cells, pens, len);
        }
        List<String> cells = new ArrayList<>();
        List<String> pens = new ArrayList<>();
        int len = rnd.nextInt(COLS + 1);
        String pen = "";
        for (int i = 0; i < len; i++) {
            if (rnd.nextInt(4) == 0) pen = PENS[rnd.nextInt(PENS.length)];
            cells.add(GLYPHS[rnd.nextInt(GLYPHS.length)]);
            pens.add(pen);
        }
        return assemble(cells, pens, len);
    }

    private static String assemble(List<String> cells, List<String> pens, int len) {
        StringBuilder sb = new StringBuilder();
        int width = 0;
        for (int i = 0; i < cells.size() && i < len; i++) {
            int w = Width.columns(cells.get(i));
            if (width + w > COLS) break;
            sb.append(Ansi.CSI).append("0;").append(pens.get(i)).append('m').append(cells.get(i));
            width += w;
        }
        return sb.append(Ansi.RESET).toString();
    }

    private static String pulse(int frame) {
        return Ansi.sgr("38;2;" + frame + ";" + frame + ";200") + "●" + Ansi.RESET;
    }

    private static String bar(int cells) {
        char[] g = new char[cells];
        Style[] s = new Style[cells];
        for (int i = 0; i < cells; i++) {
            g[i] = '█';
            s[i] = Style.EMPTY.underline().foreground(60 + i * 4, 155 - i, 255);
        }
        return Style.renderRuns(g, s);
    }
}
