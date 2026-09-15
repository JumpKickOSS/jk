// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.terminal.Width;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Minimal viewport that applies the CSI the live plan region emits: cursor-up, erase, wrap, and
 * scroll. Byte-stream assertions cannot see a header that scrolled into scrollback; this can.
 */
final class LiveRegionScreen {

    private final int rows;
    private final int cols;
    private final int[][] cells;
    private final List<String> scrollback = new ArrayList<>();
    private int row;
    private int col;
    private boolean pendingWrap;

    LiveRegionScreen(int rows, int cols) {
        if (rows < 1 || cols < 1) throw new IllegalArgumentException("rows and cols must be >= 1");
        this.rows = rows;
        this.cols = cols;
        this.cells = new int[rows][cols];
    }

    /** Fill the viewport with rows of {@code ~} so the cursor sits on the last line — a used TTY. */
    static LiveRegionScreen filled(int rows, int cols) {
        LiveRegionScreen s = new LiveRegionScreen(rows, cols);
        for (int i = 0; i < rows; i++) s.write("~\n");
        return s;
    }

    void write(String s) {
        if (s == null || s.isEmpty()) return;
        for (int i = 0; i < s.length(); ) {
            char ch = s.charAt(i);
            if (ch == '\033') {
                i = consumeEsc(s, i);
                continue;
            }
            if (ch == '\n') {
                newline();
                i++;
                continue;
            }
            if (ch == '\r') {
                col = 0;
                pendingWrap = false;
                i++;
                continue;
            }
            if (ch == '\b') {
                if (col > 0) col--;
                pendingWrap = false;
                i++;
                continue;
            }
            int cp = s.codePointAt(i);
            put(cp);
            i += Character.charCount(cp);
        }
    }

    String row(int r) {
        if (r < 0 || r >= rows) return "";
        StringBuilder sb = new StringBuilder(cols);
        for (int c = 0; c < cols; c++) {
            int cp = cells[r][c];
            if (cp > 0) sb.appendCodePoint(cp);
            else sb.append(' ');
        }
        return sb.toString().stripTrailing();
    }

    List<String> viewport() {
        List<String> out = new ArrayList<>(rows);
        for (int r = 0; r < rows; r++) out.add(row(r));
        return out;
    }

    List<String> scrollback() {
        return List.copyOf(scrollback);
    }

    int countBuildHeaders() {
        return countMatching(LiveRegionScreen::isBuildHeader);
    }

    int countMatching(Predicate<String> pred) {
        int n = 0;
        for (String line : scrollback) {
            if (pred.test(line)) n++;
        }
        for (int r = 0; r < rows; r++) {
            if (pred.test(row(r))) n++;
        }
        return n;
    }

    static boolean isBuildHeader(String line) {
        if (line == null) return false;
        String t = line.strip();
        if (!t.contains("Build")) return false;
        // Completions are `✓ [n of m] …`; the module caption is `…building module…`.
        if (t.contains(" of ") || t.contains("building module") || t.contains("building modules")) {
            return false;
        }
        return true;
    }

    private void put(int cp) {
        int w = Width.wcwidth(cp);
        if (w < 0) return; // C0/C1 — same drop as truncateVisible
        if (w == 0) return;
        if (pendingWrap) {
            newline();
            pendingWrap = false;
        }
        if (col + w > cols) newline();
        if (row >= rows) scroll();
        cells[row][col] = cp;
        for (int i = 1; i < w && col + i < cols; i++) cells[row][col + i] = 0;
        col += w;
        if (col >= cols) {
            col = cols;
            pendingWrap = true;
        }
    }

    private void newline() {
        pendingWrap = false;
        row++;
        col = 0;
        if (row >= rows) scroll();
    }

    private void scroll() {
        scrollback.add(row(0));
        for (int r = 0; r < rows - 1; r++) {
            System.arraycopy(cells[r + 1], 0, cells[r], 0, cols);
        }
        Arrays.fill(cells[rows - 1], 0);
        row = rows - 1;
        col = 0;
    }

    private int consumeEsc(String s, int i) {
        if (i + 1 >= s.length()) return s.length();
        char n = s.charAt(i + 1);
        if (n == '[') return consumeCsi(s, i);
        if (n == ']') return consumeOsc(s, i);
        return i + 2;
    }

    private int consumeOsc(String s, int i) {
        int j = i + 2;
        while (j < s.length()) {
            char c = s.charAt(j);
            if (c == '\u0007') return j + 1;
            if (c == '\033' && j + 1 < s.length() && s.charAt(j + 1) == '\\') return j + 2;
            j++;
        }
        return s.length();
    }

    private int consumeCsi(String s, int i) {
        int j = i + 2;
        if (j < s.length() && s.charAt(j) == '?') j++;
        int p1 = 0;
        int p2 = 0;
        int which = 1;
        boolean saw = false;
        while (j < s.length()) {
            char c = s.charAt(j);
            if (c >= '0' && c <= '9') {
                int d = c - '0';
                if (which == 1) p1 = p1 * 10 + d;
                else p2 = p2 * 10 + d;
                saw = true;
                j++;
                continue;
            }
            if (c == ';') {
                which = 2;
                saw = true;
                j++;
                continue;
            }
            if (c >= '@' && c <= '~') {
                applyCsi(c, saw ? p1 : -1, saw && which == 2 ? p2 : -1);
                return j + 1;
            }
            j++;
        }
        return s.length();
    }

    private void applyCsi(char cmd, int p1, int p2) {
        pendingWrap = false;
        switch (cmd) {
            case 'A' -> row = Math.max(0, row - def1(p1));
            case 'B' -> row = Math.min(rows - 1, row + def1(p1));
            case 'C' -> col = Math.min(cols, col + def1(p1));
            case 'D' -> col = Math.max(0, col - def1(p1));
            case 'F' -> {
                row = Math.max(0, row - def1(p1));
                col = 0;
            }
            case 'G' -> col = Math.max(0, Math.min(cols, Math.max(1, p1 < 0 ? 1 : p1) - 1));
            case 'H' -> {
                int rr = Math.max(1, p1 < 0 ? 1 : p1);
                int cc = Math.max(1, p2 < 0 ? 1 : p2);
                row = Math.min(rows - 1, rr - 1);
                col = Math.min(cols, cc - 1);
            }
            case 'J' -> eraseDisplay(p1 < 0 ? 0 : p1);
            case 'K' -> eraseLine(p1 < 0 ? 0 : p1);
            case 'L' -> insertLines(Math.max(1, p1 < 0 ? 1 : p1));
            default -> {
                // SGR, cursor show/hide, and other non-geometry CSI
            }
        }
    }

    private static int def1(int p) {
        return p <= 0 ? 1 : p;
    }

    private void eraseDisplay(int mode) {
        if (mode == 2) {
            for (int r = 0; r < rows; r++) Arrays.fill(cells[r], 0);
            return;
        }
        if (mode == 1) {
            for (int r = 0; r < row; r++) Arrays.fill(cells[r], 0);
            eraseLine(1);
            return;
        }
        eraseLine(0);
        for (int r = row + 1; r < rows; r++) Arrays.fill(cells[r], 0);
    }

    private void eraseLine(int mode) {
        int from = 0;
        int to = cols;
        if (mode == 0) from = Math.min(col, cols);
        else if (mode == 1) to = Math.min(col + 1, cols);
        for (int c = from; c < to; c++) cells[row][c] = 0;
    }

    private void insertLines(int n) {
        if (n < 1) return;
        for (int r = rows - 1; r >= row + n; r--) {
            System.arraycopy(cells[r - n], 0, cells[r], 0, cols);
        }
        for (int r = row; r < Math.min(rows, row + n); r++) Arrays.fill(cells[r], 0);
    }
}
