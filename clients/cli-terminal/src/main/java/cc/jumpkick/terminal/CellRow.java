// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One painted row as the terminal holds it: cells left to right, each a glyph (a base code point
 * plus any zero-width marks after it), its width in columns, its {@link Pen} and its OSC 8 link.
 * SGR and OSC 8 in the source become cell state; every other escape and control character is
 * dropped, as a clipped live row carries none that move the cursor.
 */
final class CellRow {

    /** One glyph and how it is painted. {@code col} is where it starts. */
    record Cell(
            int col,
            String glyph,
            int width,
            Pen pen,
            @Nullable String link,
            boolean uncertain) {

        /** Same glyph, width and paint: a cell the terminal already shows needs no write. */
        boolean paintsLike(Cell o) {
            return col == o.col
                    && width == o.width
                    && glyph.equals(o.glyph)
                    && pen.equals(o.pen)
                    && Objects.equals(link, o.link);
        }
    }

    static final CellRow EMPTY = new CellRow("", List.of(), 0, 0);

    final String source;
    final List<Cell> cells;
    /** Columns the row covers: the end of its last cell. */
    final int width;
    /** The width the row was clipped to; 0 when it was not. */
    final int clip;

    private CellRow(String source, List<Cell> cells, int width, int clip) {
        this.source = source;
        this.cells = cells;
        this.width = width;
        this.clip = clip;
    }

    /** This row cut to the cells that end within {@code cols} columns. */
    CellRow clip(int cols) {
        if (cols == clip) return this;
        List<Cell> kept = new ArrayList<>(cells.size());
        int end = 0;
        for (Cell c : cells) {
            if (c.col() + c.width() > cols) break;
            kept.add(c);
            end = c.col() + c.width();
        }
        return new CellRow(source, kept.size() == cells.size() ? cells : List.copyOf(kept), end, cols);
    }

    /** Whether the terminal shows the same thing for both rows. */
    boolean same(CellRow o) {
        return width == o.width && cells.equals(o.cells);
    }

    /**
     * The column ranges {@code [start, end)} of this row's cells that {@code had} does not already
     * paint the same, adjacent cells merged, left to right.
     */
    List<int[]> changedSpans(CellRow had) {
        Cell[] at = new Cell[had.width];
        for (Cell c : had.cells) at[c.col()] = c;
        List<int[]> spans = new ArrayList<>();
        for (Cell c : cells) {
            Cell old = c.col() < at.length ? at[c.col()] : null;
            if (old != null && old.paintsLike(c)) continue;
            int end = c.col() + c.width();
            if (!spans.isEmpty() && spans.getLast()[1] == c.col()) spans.getLast()[1] = end;
            else spans.add(new int[] {c.col(), end});
        }
        return spans;
    }

    /** The column of the first glyph of {@linkplain #uncertain uncertain} width; {@link Integer#MAX_VALUE} if none. */
    int firstUncertain() {
        for (Cell c : cells) {
            if (c.uncertain()) return c.col();
        }
        return Integer.MAX_VALUE;
    }

    /** Whether a cell of this row starts at column {@code col}: the cursor there can write on. */
    boolean startsAt(int col) {
        for (Cell c : cells) {
            if (c.col() == col) return true;
            if (c.col() > col) return false;
        }
        return false;
    }

    /** The start of the cell covering column {@code col}, or {@link #width} past the last cell. */
    int cellStartAtOrBefore(int col) {
        if (col >= width) return width;
        int start = 0;
        for (Cell c : cells) {
            if (c.col() > col) break;
            start = c.col();
        }
        return start;
    }

    static CellRow parse(String source) {
        List<Cell> cells = new ArrayList<>();
        Pen pen = Pen.DEFAULT;
        String link = null;
        int col = 0;
        for (int i = 0; i < source.length(); ) {
            char ch = source.charAt(i);
            if (ch == Ansi.ESC) {
                int end = Width.skipEscape(source, i);
                if (i + 1 < source.length() && source.charAt(i + 1) == '[' && source.charAt(end - 1) == 'm') {
                    pen = pen.apply(source.substring(i + 2, end - 1));
                } else if (source.startsWith("]8;", i + 1)) {
                    link = linkOf(source, i, end);
                }
                i = end;
                continue;
            }
            int cp = source.codePointAt(i);
            i += Character.charCount(cp);
            int w = Width.wcwidth(cp);
            if (w < 0) continue;
            if (w == 0) {
                if (!cells.isEmpty()) {
                    Cell last = cells.removeLast();
                    String glyph = last.glyph() + Character.toString(cp);
                    cells.add(new Cell(last.col(), glyph, last.width(), last.pen(), last.link(), true));
                }
                continue;
            }
            cells.add(new Cell(col, Character.toString(cp), w, pen, link, uncertain(cp)));
            col += w;
        }
        return new CellRow(source, List.copyOf(cells), col, 0);
    }

    /**
     * Whether a terminal may give {@code cp} a different width than {@link Width#wcwidth} does:
     * emoji and pictographs, and private-use glyphs (Nerd Font icons and powerline caps).
     */
    static boolean uncertain(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF) || (cp >= 0xE000 && cp <= 0xF8FF) || cp >= 0xF0000;
    }

    /** The URI an OSC 8 sequence opens, or {@code null} for the empty URI that closes a link. */
    private static @Nullable String linkOf(String s, int start, int end) {
        int params = start + 4;
        int sep = s.indexOf(';', params);
        if (sep < 0 || sep >= end) return null;
        int stop = end;
        if (s.charAt(end - 1) == Ansi.BEL) stop = end - 1;
        else if (end - 2 >= start && s.charAt(end - 2) == Ansi.ESC && s.charAt(end - 1) == '\\') stop = end - 2;
        String uri = s.substring(sep + 1, Math.max(sep + 1, stop));
        return uri.isEmpty() ? null : uri;
    }
}
