// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

import cc.jumpkick.terminal.CellRow.Cell;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Paints a live region of whole rows in place, writing only what differs from what the terminal
 * already shows.
 *
 * <p>The painter keeps the cells it last put on screen and where it left the cursor. Each {@link
 * #paint} compares the new rows with them cell by cell: a frame that changes nothing writes
 * nothing, and otherwise every row is encoded the cheapest of three ways, by exact byte count —
 * left alone, only its changed spans, or the whole row from column 0. Between two changed spans
 * the cursor either jumps or rewrites the unchanged cells between them, and every move is priced
 * among {@code \r}, {@code \n}, CUU, CUD, CUF, CUB and CHA. Colours go out as a transition from
 * the pen the cursor holds. So a frame where one glyph changed costs that glyph and its moves,
 * and a frame where every row changed comes out as a full repaint, by the same rule.
 *
 * <p>The cursor's column is trusted only as far as the painter's own widths are. After a glyph a
 * terminal may draw at another width ({@link CellRow#uncertain}), a row is rewritten from that
 * glyph onward rather than jumped into. Rows are clipped to the terminal width, so a row never
 * wraps.
 *
 * <p>Two shapes of region:
 *
 * <ul>
 * <li>{@link Park#BELOW} — between paints the cursor rests at column 0 of the row under the region,
 *     where a child process's unlocked output lands. Newlines written there since the last paint
 *     are {@code drift}: the region is that many rows further up, and the rows they left under it
 *     are erased by the next paint that changes anything.
 * <li>{@link Park#IN_ROW} — a one-row region; the cursor stays on that row.
 * </ul>
 *
 * <p>Not thread-safe: the owner paints under its own lock.
 */
public final class RegionPainter {

    /** Where the cursor rests between paints. */
    public enum Park {
        BELOW,
        IN_ROW
    }

    private final Park park;

    /** What each row of the region shows; {@code null} for a row whose content is unknown. */
    private final List<@Nullable CellRow> screen = new ArrayList<>();

    private @Nullable String status;

    public RegionPainter(Park park) {
        this.park = park;
    }

    /** Rows the region occupies on screen. */
    public int height() {
        return screen.size();
    }

    /** The source text of each row on screen, top first; an unknown row is empty. */
    public List<String> rows() {
        List<String> out = new ArrayList<>(screen.size());
        for (CellRow row : screen) out.add(row == null ? "" : row.source);
        return out;
    }

    /**
     * Append the bytes that take the region from what is on screen to {@code lines}, each clipped to
     * {@code cols} columns ({@code cols <= 0}: no clip). Appends nothing when the screen already
     * shows them and no drift is pending.
     *
     * @param drift newlines written at the park since the last paint ({@link Park#BELOW} only)
     * @param maxClimb rows the cursor can climb before it would leave the viewport; a region whose
     *     top is further up than that is treated as lost and repainted from as high as it can reach
     */
    public void paint(StringBuilder out, List<String> lines, int cols, int drift, int maxClimb) {
        List<CellRow> next = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            CellRow had = i < screen.size() ? screen.get(i) : null;
            int clip = Math.max(0, cols);
            next.add(
                    had != null && had.clip == clip && had.source.equals(line)
                            ? had
                            : CellRow.parse(line).clip(clip));
        }
        if (park == Park.IN_ROW) {
            paintInRow(out, next, cols);
        } else {
            paintBelow(out, next, cols, Math.max(0, drift), Math.max(0, maxClimb));
        }
    }

    /**
     * Append the climb to the region's top and an erase of it and everything under it, then forget
     * it: the cursor is left at column 0 of the former top row, and the next paint starts a new
     * region there.
     */
    public void lift(StringBuilder out, int drift, int maxClimb) {
        if (park == Park.IN_ROW) {
            out.append('\r').append(Ansi.ERASE_LINE_TO_END);
        } else {
            int up = Math.min(screen.size() + Math.max(0, drift), Math.max(0, maxClimb));
            if (up > 0) out.append(Moves.up(up));
            out.append('\r').append(Ansi.ERASE_DISPLAY_TO_END);
        }
        screen.clear();
    }

    /**
     * Forget the region without writing anything: the caller already erased it and left the cursor
     * where the next region starts.
     */
    public void forget() {
        screen.clear();
    }

    /**
     * Keep the region's height but forget what its rows show, so the next paint rewrites every row
     * in place: after a resize changed the clip, or after something else wrote over the region.
     */
    public void invalidate() {
        screen.replaceAll(r -> null);
    }

    /**
     * Append {@code sequence} (an OSC taskbar or other status string) only when it differs from the
     * last one sent.
     */
    public void status(StringBuilder out, String sequence) {
        if (sequence.equals(status)) return;
        status = sequence;
        out.append(sequence);
    }

    /** Forget the last status sent, so the next {@link #status} is written whatever it is. */
    public void forgetStatus() {
        status = null;
    }

    private void paintInRow(StringBuilder out, List<CellRow> next, int cols) {
        if (next.size() > 1) throw new IllegalArgumentException("an IN_ROW region is one row");
        CellRow row = next.isEmpty() ? CellRow.EMPTY : next.getFirst();
        CellRow had = screen.isEmpty() ? null : screen.getFirst();
        // Column unknown: the row was written by this painter, but nothing proves where it stopped.
        Pass pass = new Pass(cols, 0, 0);
        Pass done = pass.row(had, row);
        if (done == null) return;
        done.endPen();
        out.append(done.sb);
        screen.clear();
        screen.add(row);
    }

    private void paintBelow(StringBuilder out, List<CellRow> next, int cols, int drift, int maxClimb) {
        int oldHeight = screen.size();
        int parkRow = oldHeight + drift;
        int newHeight = next.size();
        boolean stale = parkRow > newHeight;
        int top = -1;
        for (int i = 0; i < newHeight; i++) {
            CellRow had = i < oldHeight ? screen.get(i) : null;
            if (had == null || !had.same(next.get(i))) {
                top = i;
                break;
            }
        }
        if (top < 0 && !stale) return;
        Pass pass = new Pass(cols, parkRow, parkRow);
        List<@Nullable CellRow> known = new ArrayList<>(screen);
        int reach = top < 0 ? newHeight : top;
        if (parkRow - reach > maxClimb) {
            // The region's top scrolled out of reach: wipe what can be reached and start over there.
            int up = Math.min(parkRow, maxClimb);
            if (up > 0) pass.sb.append(Moves.up(up));
            pass.sb.append('\r').append(Ansi.ERASE_DISPLAY_TO_END);
            pass = new Pass(cols, 0, 0);
            known.clear();
            stale = false;
        }
        for (int i = 0; i < newHeight; i++) {
            CellRow had = i < known.size() ? known.get(i) : null;
            CellRow row = next.get(i);
            if (had != null && had.same(row)) continue;
            pass = Objects.requireNonNull(pass.at(i, had, row));
        }
        pass.endPen();
        pass.toPark(newHeight, stale);
        out.append(pass.sb);
        screen.clear();
        screen.addAll(next);
    }

    /** UTF-8 bytes of {@code s}: what a frame costs on the wire. */
    static int bytes(CharSequence s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) n++;
            else if (c < 0x800) n += 2;
            else if (Character.isHighSurrogate(c)) {
                n += 4;
                i++;
            } else n += 3;
        }
        return n;
    }

    private static <T extends Pass> T cheapest(List<T> candidates) {
        T best = candidates.getFirst();
        int bestCost = bytes(best.sb);
        for (T c : candidates) {
            int cost = bytes(c.sb);
            if (cost < bestCost) {
                best = c;
                bestCost = cost;
            }
        }
        return best;
    }

    /** Cursor-move encodings, the count left out where it defaults to 1. */
    static final class Moves {
        private Moves() {}

        static String up(int n) {
            return Ansi.CSI + (n == 1 ? "" : n) + "A";
        }

        static String down(int n) {
            return Ansi.CSI + (n == 1 ? "" : n) + "B";
        }

        static String forward(int n) {
            return Ansi.CSI + (n == 1 ? "" : n) + "C";
        }

        static String back(int n) {
            return Ansi.CSI + (n == 1 ? "" : n) + "D";
        }

        static String column(int col) {
            return Ansi.CSI + (col == 0 ? "" : col + 1) + "G";
        }
    }

    /**
     * One way of writing a frame so far: the bytes, and the terminal state they leave — cursor row
     * and column, pen and link. {@link #copy} forks it so alternatives can be priced side by side.
     */
    private static final class Pass {
        final StringBuilder sb;
        final int cols;
        /** Cursor row, relative to the region's top. */
        int row;
        /** Cursor column; -1 when unknown. */
        int col = -1;
        /** Highest row known to exist: moving below it takes {@code \n}, which scrolls; CUD does not. */
        int bottom;
        /** The pen the terminal holds: every frame and every styled line ends in the default. */
        Pen pen = Pen.DEFAULT;

        @Nullable
        String link;

        /** The last write ended in the last column, where the cursor waits to wrap. */
        boolean edge;

        Pass(int cols, int row, int bottom) {
            this.sb = new StringBuilder(256);
            this.cols = cols;
            this.row = row;
            this.bottom = bottom;
        }

        private Pass(Pass o) {
            this.sb = new StringBuilder(o.sb);
            this.cols = o.cols;
            this.row = o.row;
            this.col = o.col;
            this.bottom = o.bottom;
            this.pen = o.pen;
            this.link = o.link;
            this.edge = o.edge;
        }

        Pass copy() {
            return new Pass(this);
        }

        /** The cheapest way to move to row {@code target} and bring it from {@code had} to {@code row}. */
        @Nullable
        Pass at(int target, @Nullable CellRow had, CellRow row) {
            List<Pass> ways = new ArrayList<>(2);
            if (target < this.row) {
                Pass p = copy();
                p.sb.append(Moves.up(this.row - target));
                p.row = target;
                ways.add(p);
            } else if (target > this.row) {
                int n = target - this.row;
                Pass nl = copy();
                nl.sb.repeat('\n', n);
                nl.row = target;
                nl.col = -1;
                nl.bottom = Math.max(nl.bottom, target);
                ways.add(nl);
                if (target <= bottom) {
                    Pass cud = copy();
                    cud.sb.append(Moves.down(n));
                    cud.row = target;
                    ways.add(cud);
                }
            } else {
                ways.add(copy());
            }
            List<Pass> done = new ArrayList<>(ways.size());
            for (Pass w : ways) {
                if (w.row != this.row) w.edge = false;
                Pass d = w.row(had, row);
                done.add(d == null ? w : d);
            }
            return cheapest(done);
        }

        /**
         * Bring the cursor's row from {@code had} ({@code null}: unknown) to {@code row}, the
         * cheaper of a whole-row rewrite and a write of the changed spans; {@code null} when the row
         * already shows it.
         */
        @Nullable
        Pass row(@Nullable CellRow had, CellRow row) {
            if (had != null && had.same(row)) return null;
            Pass whole = copy();
            whole.moveTo(0);
            whole.write(row, 0, row.width);
            if (had == null || had.width > row.width) whole.erase();
            if (had == null) return whole;
            Pass spans = copy().spans(had, row);
            return bytes(spans.sb) < bytes(whole.sb) ? spans : whole;
        }

        /** Write only the cells of {@code row} that differ from {@code had}, then erase a shorter tail. */
        private Pass spans(CellRow had, CellRow row) {
            List<int[]> spans = row.changedSpans(had);
            boolean tail = had.width > row.width;
            int end = tail ? row.width : spans.isEmpty() ? 0 : spans.getLast()[1];
            int uncertain = Math.min(had.firstUncertain(), row.firstUncertain());
            if (uncertain < end) {
                // Past a glyph of uncertain width, rewrite contiguously from that glyph's cell.
                int from = row.cellStartAtOrBefore(uncertain);
                List<int[]> kept = new ArrayList<>();
                for (int[] s : spans) {
                    if (s[1] <= from) kept.add(s);
                    else {
                        from = Math.min(from, s[0]);
                        break;
                    }
                }
                kept.add(new int[] {from, end});
                spans = kept;
            }
            for (int[] s : spans) {
                if (col >= 0 && col < s[0] && row.startsAt(col)) {
                    // Jump the gap, or rewrite the unchanged cells in it: whichever is fewer bytes.
                    Pass fill = copy();
                    fill.write(row, col, s[0]);
                    Pass jump = copy();
                    jump.moveTo(s[0]);
                    Pass fillDone = fill.write(row, s[0], s[1]);
                    Pass jumpDone = jump.write(row, s[0], s[1]);
                    Pass best = cheapest(List.of(jumpDone, fillDone));
                    adopt(best);
                    continue;
                }
                if (col != s[0] && s[0] > 0) {
                    // From an unknown column: a column move, or a return and the cells before it.
                    Pass fill = copy();
                    fill.moveTo(0);
                    fill.write(row, 0, s[0]);
                    Pass jump = copy();
                    jump.moveTo(s[0]);
                    adopt(cheapest(List.of(jump.write(row, s[0], s[1]), fill.write(row, s[0], s[1]))));
                    continue;
                }
                moveTo(s[0]);
                write(row, s[0], s[1]);
            }
            if (tail) {
                moveTo(row.width);
                erase();
            }
            return this;
        }

        private void adopt(Pass o) {
            sb.setLength(0);
            sb.append(o.sb);
            row = o.row;
            col = o.col;
            bottom = o.bottom;
            pen = o.pen;
            link = o.link;
            edge = o.edge;
        }

        /** The cheapest move to column {@code target} on this row. */
        void moveTo(int target) {
            if (col == target) return;
            edge = false;
            if (target == 0) {
                sb.append('\r');
                col = 0;
                return;
            }
            String best = Moves.column(target);
            String cr = "\r" + Moves.forward(target);
            if (cr.length() < best.length()) best = cr;
            if (col >= 0) {
                String rel = target > col ? Moves.forward(target - col) : Moves.back(col - target);
                if (rel.length() < best.length()) best = rel;
            }
            sb.append(best);
            col = target;
        }

        /** Write the cells of {@code row} starting in columns {@code [from, to)}; the cursor is at {@code from}. */
        Pass write(CellRow row, int from, int to) {
            boolean lost = false;
            for (Cell c : row.cells) {
                if (c.col() < from || c.col() >= to) continue;
                if (!pen.equals(c.pen())) {
                    sb.append(pen.to(c.pen()));
                    pen = c.pen();
                }
                if (!Objects.equals(link, c.link())) {
                    sb.append(Ansi.OSC)
                            .append("8;;")
                            .append(c.link() == null ? "" : c.link())
                            .append(Ansi.ST);
                    link = c.link();
                }
                sb.append(c.glyph());
                lost |= c.uncertain();
                col = c.col() + c.width();
            }
            if (col < 0) col = to;
            // At the right edge the cursor waits to wrap; a relative move from there lands one short.
            edge = cols > 0 && col >= cols;
            if (lost || edge) col = -1;
            return this;
        }

        /** Erase from the cursor to the end of the row, in the default background. */
        void erase() {
            // Waiting to wrap at the last column, the cursor sits on that column: EL would erase it.
            if (edge) return;
            if (pen.tintsErase()) {
                sb.append(pen.to(Pen.DEFAULT));
                pen = Pen.DEFAULT;
            }
            sb.append(Ansi.ERASE_LINE_TO_END);
        }

        /** Close any link and return the pen to default, so whoever writes next starts clean. */
        void endPen() {
            if (link != null) {
                sb.append(Ansi.OSC).append("8;;").append(Ansi.ST);
                link = null;
            }
            if (!pen.equals(Pen.DEFAULT)) {
                sb.append(pen.to(Pen.DEFAULT));
                pen = Pen.DEFAULT;
            }
        }

        /** Rest the cursor at column 0 under the region's {@code height} rows, erasing anything below. */
        void toPark(int height, boolean stale) {
            edge = false;
            if (row > height) {
                sb.append(Moves.up(row - height));
                row = height;
            } else if (row < height) {
                int n = height - row;
                String cud = Moves.down(n);
                if (height <= bottom && cud.length() + (col == 0 ? 0 : 1) < n + 1) {
                    sb.append(cud);
                } else {
                    sb.repeat('\n', n);
                    col = -1;
                    bottom = Math.max(bottom, height);
                }
                row = height;
            }
            if (col != 0) sb.append('\r');
            col = 0;
            if (stale) sb.append(Ansi.ERASE_DISPLAY_TO_END);
        }
    }
}
