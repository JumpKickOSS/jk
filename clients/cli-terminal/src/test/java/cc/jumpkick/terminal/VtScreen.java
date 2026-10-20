// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A small VT viewport for painter tests: cursor moves (CR, LF, CUU, CUD, CUF, CUB, CHA), EL, ED,
 * SGR and OSC 8, pending wrap at the right edge, wide glyphs, and scrolling at the bottom. Each
 * cell keeps its glyph, pen and link, so a test can compare what a terminal shows with what a
 * frame meant.
 */
final class VtScreen {

    /** What one column shows. A wide glyph's second column is a {@code null} glyph. */
    record Shown(@Nullable String glyph, Pen pen, @Nullable String link) {
        static final Shown BLANK = new Shown(" ", Pen.DEFAULT, null);
    }

    final int rows;
    final int cols;
    private final Shown[][] cells;
    int row;
    int col;
    private boolean pendingWrap;
    private Pen pen = Pen.DEFAULT;
    private @Nullable String link;
    int scrolled;
    /** Whether LF also returns the carriage, as a tty's ONLCR output mode does. */
    boolean onlcr = true;

    VtScreen(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
        this.cells = new Shown[rows][cols];
        for (Shown[] r : cells) Arrays.fill(r, Shown.BLANK);
    }

    void write(CharSequence s) {
        for (int i = 0; i < s.length(); ) {
            char ch = s.charAt(i);
            if (ch == Ansi.ESC) {
                i = escape(s.toString(), i);
                continue;
            }
            if (ch == '\r') {
                col = 0;
                pendingWrap = false;
                i++;
                continue;
            }
            if (ch == '\n') {
                lineFeed();
                if (onlcr) col = 0;
                i++;
                continue;
            }
            int cp = Character.codePointAt(s, i);
            i += Character.charCount(cp);
            put(cp);
        }
    }

    /** The cells of row {@code r} as text with each column's pen, for assertions. */
    Shown[] row(int r) {
        return cells[r].clone();
    }

    /** Row {@code r}'s glyphs, trailing blanks dropped. */
    String text(int r) {
        StringBuilder sb = new StringBuilder();
        for (Shown s : cells[r]) {
            if (s.glyph() != null) sb.append(s.glyph());
        }
        return sb.toString().stripTrailing();
    }

    private void put(int cp) {
        int w = Width.wcwidth(cp);
        if (w < 0) return;
        if (w == 0) {
            int c = col - 1;
            while (c > 0 && cells[row][c].glyph() == null) c--;
            if (c >= 0) {
                Shown s = cells[row][c];
                cells[row][c] = new Shown(s.glyph() + Character.toString(cp), s.pen(), s.link());
            }
            return;
        }
        if (pendingWrap || col + w > cols) {
            lineFeed();
            col = 0;
            pendingWrap = false;
        }
        clearWideAt(col);
        if (w == 2) clearWideAt(col + 1);
        cells[row][col] = new Shown(Character.toString(cp), pen, link);
        if (w == 2) cells[row][col + 1] = new Shown(null, pen, link);
        col += w;
        if (col >= cols) {
            col = cols - 1;
            pendingWrap = true;
        }
    }

    /** Overwriting either half of a wide glyph blanks the other half, as xterm does. */
    private void clearWideAt(int c) {
        if (c >= cols) return;
        if (cells[row][c].glyph() == null && c > 0) cells[row][c - 1] = Shown.BLANK;
        if (c + 1 < cols && cells[row][c + 1].glyph() == null) cells[row][c + 1] = Shown.BLANK;
    }

    private void lineFeed() {
        pendingWrap = false;
        if (row == rows - 1) {
            for (int r = 0; r < rows - 1; r++) cells[r] = cells[r + 1];
            cells[rows - 1] = new Shown[cols];
            Arrays.fill(cells[rows - 1], Shown.BLANK);
            scrolled++;
        } else {
            row++;
        }
    }

    private int escape(String s, int i) {
        int end = Width.skipEscape(s, i);
        if (i + 1 >= s.length()) return end;
        char kind = s.charAt(i + 1);
        if (kind == ']') {
            if (s.startsWith("]8;", i + 1)) {
                int sep = s.indexOf(';', i + 4);
                int stop = s.charAt(end - 1) == Ansi.BEL ? end - 1 : end - 2;
                String uri = s.substring(sep + 1, stop);
                link = uri.isEmpty() ? null : uri;
            }
            return end;
        }
        if (kind != '[') return end;
        String params = s.substring(i + 2, end - 1);
        char cmd = s.charAt(end - 1);
        if (cmd == 'm') {
            pen = pen.apply(params);
            return end;
        }
        if (params.startsWith("?")) return end;
        int n = params.isEmpty() ? -1 : Integer.parseInt(params);
        int count = n <= 0 ? 1 : n;
        switch (cmd) {
            case 'A' -> {
                row = Math.max(0, row - count);
                pendingWrap = false;
            }
            case 'B' -> {
                row = Math.min(rows - 1, row + count);
                pendingWrap = false;
            }
            case 'C' -> {
                col = Math.min(cols - 1, col + count);
                pendingWrap = false;
            }
            case 'D' -> {
                col = Math.max(0, col - count);
                pendingWrap = false;
            }
            case 'G' -> {
                col = Math.min(cols - 1, count - 1);
                pendingWrap = false;
            }
            case 'K' -> {
                if (n > 0) throw new IllegalStateException("EL " + n + " unsupported");
                eraseFrom(row, col);
            }
            case 'J' -> {
                if (n > 0) throw new IllegalStateException("ED " + n + " unsupported");
                eraseFrom(row, col);
                for (int r = row + 1; r < rows; r++) eraseFrom(r, 0);
            }
            default -> throw new IllegalStateException("unexpected CSI " + params + cmd);
        }
        return end;
    }

    private void eraseFrom(int r, int c) {
        Shown blank = new Shown(" ", new Pen(0, null, pen.bg()), null);
        if (c > 0 && cells[r][c].glyph() == null) cells[r][c - 1] = blank;
        for (int x = c; x < cols; x++) cells[r][x] = blank;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) sb.append(Objects.toString(text(r))).append('\n');
        return sb.toString();
    }
}
