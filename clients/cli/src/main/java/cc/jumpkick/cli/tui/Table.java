// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Theme;
import java.util.ArrayList;
import java.util.List;

/**
 * Box-drawn table whose title line is a {@link JkWedge}. Appended tables hide their title and
 * column headers by default and snap their rails to the nearest parent column edge.
 */
public final class Table implements Widget {

    public enum Align {
        LEFT,
        RIGHT,
        CENTER
    }

    public enum Append {
        /** Same ordered column names → {@link #MERGE}; otherwise {@link #SECTION}. */
        AUTO,
        /** Concatenate rows into the first table's body. */
        MERGE,
        /** Independent section; snap columns to the parent grid. */
        SECTION
    }

    public enum RowKind {
        DATA,
        SEPARATOR,
        SPAN
    }

    public record Column(String name, Align align) {
        public Column(String name) {
            this(name == null ? "" : name, Align.LEFT);
        }

        public Column {
            name = name == null ? "" : name;
            align = align == null ? Align.LEFT : align;
        }
    }

    public record Cell(RichText text, int colSpan) {
        public Cell {
            text = text == null ? RichText.empty() : text;
            if (colSpan < 1) colSpan = 1;
        }

        public static Cell of(String text) {
            return new Cell(RichText.plain(text == null ? "" : text), 1);
        }

        public static Cell of(RichText text) {
            return new Cell(text, 1);
        }

        public Cell span(int n) {
            return new Cell(text, n);
        }
    }

    public record Row(RowKind kind, List<Cell> cells, boolean highlight) {
        public Row {
            kind = kind == null ? RowKind.DATA : kind;
            cells = cells == null ? List.of() : List.copyOf(cells);
        }

        public Row(RowKind kind, List<Cell> cells) {
            this(kind, cells, false);
        }

        public Row emphasized() {
            return new Row(kind, cells, true);
        }

        public static Row data(RichText... cells) {
            var list = new ArrayList<Cell>();
            if (cells != null) {
                for (RichText c : cells) list.add(Cell.of(c));
            }
            return new Row(RowKind.DATA, list);
        }

        public static Row data(String... cells) {
            var list = new ArrayList<Cell>();
            if (cells != null) {
                for (String c : cells) list.add(Cell.of(c));
            }
            return new Row(RowKind.DATA, list);
        }

        public static Row separator() {
            return new Row(RowKind.SEPARATOR, List.of());
        }

        public static Row span(Cell... cells) {
            return new Row(RowKind.SPAN, cells == null ? List.of() : List.of(cells));
        }
    }

    private Icon icon = Icon.menu();
    private String title;
    /** {@code null} = context default (root shows, appended hides). */
    private Boolean showTitle;

    private Boolean showColumns;
    private boolean warning;
    private boolean rowSeparators;
    private final List<Column> columns = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<Table> appended = new ArrayList<>();

    public Table(String title) {
        this.title = title == null ? "" : title;
    }

    /** Italic header text when ANSI is on. */
    public static String headerCell(String text) {
        String s = text == null ? "" : text;
        if (s.isEmpty() || !Theme.active().isAnsi()) return s;
        return Theme.colorize(s, org.jline.utils.AttributedStyle.DEFAULT.italic());
    }

    public static int visibleWidth(String s) {
        return RenderContext.visibleWidth(s);
    }

    /** String-cell table (replaces {@code BoxTable.render}). */
    public static Table of(String title, List<String> headers, List<? extends List<String>> rows) {
        Table table = new Table(title).columns(headers.toArray(String[]::new));
        int cols = headers.size();
        for (var row : rows) {
            String[] cells = new String[cols];
            for (int i = 0; i < cols; i++) {
                cells[i] = i < row.size() && row.get(i) != null ? row.get(i) : "";
            }
            table.row(cells);
        }
        return table;
    }

    public static Table ofWarning(String title, List<String> headers, List<? extends List<String>> rows) {
        return of(title, headers, rows).warning(true).rowSeparators(true);
    }

    public static List<String> render(String title, List<String> headers, List<? extends List<String>> rows) {
        return of(title, headers, rows).render(RenderContext.current());
    }

    public static List<String> renderWarning(String title, List<String> headers, List<? extends List<String>> rows) {
        return ofWarning(title, headers, rows).render(RenderContext.current());
    }

    public Table icon(Icon icon) {
        this.icon = icon == null ? Icon.menu() : icon;
        return this;
    }

    public Table showTitle(boolean show) {
        this.showTitle = show;
        return this;
    }

    public Table showColumns(boolean show) {
        this.showColumns = show;
        return this;
    }

    public Table warning(boolean on) {
        this.warning = on;
        if (on) this.icon = Icon.bang();
        return this;
    }

    /** Insert a divider between every data row (destructive confirm tables). */
    public Table rowSeparators(boolean on) {
        this.rowSeparators = on;
        return this;
    }

    public Table columns(String... names) {
        this.columns.clear();
        if (names != null) {
            for (String name : names) this.columns.add(new Column(name));
        }
        return this;
    }

    public Table columns(Column... cols) {
        this.columns.clear();
        if (cols != null) {
            for (Column c : cols) this.columns.add(c);
        }
        return this;
    }

    public Table row(RichText... cells) {
        rows.add(Row.data(cells));
        return this;
    }

    public Table row(String... cells) {
        rows.add(Row.data(cells));
        return this;
    }

    public Table row(Row row) {
        if (row != null) rows.add(row);
        return this;
    }

    public Table append(Table other) {
        return append(other, Append.AUTO);
    }

    public Table append(Table other, Append mode) {
        if (other == null) return this;
        Append resolved = mode == null ? Append.AUTO : mode;
        if (resolved == Append.AUTO) {
            resolved = sameColumns(this.columns, other.columns) ? Append.MERGE : Append.SECTION;
        }
        if (resolved == Append.MERGE) {
            rows.addAll(other.rows);
            return this;
        }
        appended.add(other);
        return this;
    }

    public List<Column> columns() {
        return List.copyOf(columns);
    }

    @Override
    public List<String> render(RenderContext ctx) {
        return paint(ctx, false);
    }

    private boolean effectiveShowTitle(boolean isAppended) {
        return showTitle != null ? showTitle : !isAppended;
    }

    private boolean effectiveShowColumns(boolean isAppended) {
        return showColumns != null ? showColumns : !isAppended;
    }

    private static boolean sameColumns(List<Column> a, List<Column> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).name().equals(b.get(i).name())) return false;
        }
        return true;
    }

    private List<String> paint(RenderContext ctx, boolean isAppended) {
        boolean plain = !ctx.ansi();
        List<Column> cols = columns.isEmpty() ? List.of(new Column("")) : List.copyOf(columns);
        int n = cols.size();
        int[] widths = naturalWidths(cols, rows, ctx, plain);

        // First pass: snap each appended section onto this grid, expanding parent widths as needed.
        List<int[]> childSpans = new ArrayList<>();
        List<int[]> childWidths = new ArrayList<>();
        for (Table child : appended) {
            int[] cw = naturalWidths(child.columnsView(), child.rows, ctx, plain);
            int[] spans = snapSpans(widths, cw);
            expandParentToFit(widths, spans, cw);
            childSpans.add(spans);
            childWidths.add(cw);
        }

        // Title may force the last column wider.
        int inner = innerWidth(widths);
        if (effectiveShowTitle(isAppended)) {
            int deficit = minTitleWidth(title, ctx) - (inner + 2);
            if (deficit > 0 && n > 0) {
                widths[n - 1] += deficit;
                inner += deficit;
            }
        }
        // Re-snap after title expansion so child spans still cover the grid.
        for (int i = 0; i < appended.size(); i++) {
            Table child = appended.get(i);
            int[] cw = naturalWidths(child.columnsView(), child.rows, ctx, plain);
            int[] spans = snapSpans(widths, cw);
            expandParentToFit(widths, spans, cw);
            childSpans.set(i, spans);
            childWidths.set(i, snappedChildWidths(widths, spans));
        }
        inner = innerWidth(widths);
        int total = inner + 2;

        List<String> out = new ArrayList<>();
        JkWedge wedge = titleWedge();
        if (effectiveShowTitle(isAppended)) {
            out.add(wedge.renderTitleBar(ctx, total));
        }
        if (effectiveShowColumns(isAppended)) {
            out.add(divider(ctx, "├", "┬", "┤", widths));
            out.add(headerRow(ctx, cols, widths, plain));
        }
        List<Row> data = rows;
        boolean hasBody = hasVisibleBody(data);
        if (effectiveShowColumns(isAppended) && hasBody) {
            out.add(divider(ctx, "├", "┼", "┤", widths));
        } else if (!effectiveShowColumns(isAppended) && effectiveShowTitle(isAppended) && hasBody) {
            out.add(divider(ctx, "├", "┬", "┤", widths));
        }
        int dataIndex = 0;
        int dataCount = countData(data);
        for (int i = 0; i < data.size(); i++) {
            Row row = data.get(i);
            if (row.kind() == RowKind.SEPARATOR) {
                out.add(divider(ctx, "├", "┼", "┤", widths));
                continue;
            }
            if (row.kind() == RowKind.SPAN) {
                if (isFullSpan(row, widths.length)) {
                    out.add(divider(ctx, "├", "┴", "┤", widths));
                }
                out.add(spanRow(ctx, row, widths, plain));
            } else {
                out.add(dataRow(ctx, row, cols, widths, plain));
            }
            if (row.kind() == RowKind.DATA) {
                dataIndex++;
                if (rowSeparators && dataIndex < dataCount) {
                    out.add(divider(ctx, "├", "┼", "┤", widths));
                }
            }
        }

        boolean last = appended.isEmpty();
        if (last) {
            if (lastVisibleIsFullSpan(data, widths.length)) {
                out.add(flatClose(ctx, innerWidth(widths)));
            } else {
                out.add(divider(ctx, "╰", "┴", "╯", widths));
            }
            return out;
        }

        for (int s = 0; s < appended.size(); s++) {
            Table child = appended.get(s);
            int[] spans = childSpans.get(s);
            int[] cw = childWidths.get(s);
            boolean childLast = s == appended.size() - 1;
            out.add(joinDivider(ctx, widths, spans));
            if (child.effectiveShowTitle(true)) {
                out.add(child.titleWedge().renderTitleBar(ctx, total));
            }
            if (child.effectiveShowColumns(true)) {
                out.add(divider(ctx, "├", "┬", "┤", cw));
                out.add(headerRow(ctx, child.columnsView(), cw, plain));
                if (hasVisibleBody(child.rows)) {
                    out.add(divider(ctx, "├", "┼", "┤", cw));
                }
            }
            for (Row row : child.rows) {
                if (row.kind() == RowKind.SEPARATOR) {
                    out.add(divider(ctx, "├", "┼", "┤", cw));
                } else if (row.kind() == RowKind.SPAN) {
                    out.add(spanRow(ctx, row, cw, plain));
                } else {
                    out.add(dataRow(ctx, row, child.columnsView(), cw, plain));
                }
            }
            if (childLast) {
                out.add(divider(ctx, "╰", "┴", "╯", cw));
            }
        }
        return out;
    }

    private List<Column> columnsView() {
        return columns.isEmpty() ? List.of(new Column("")) : List.copyOf(columns);
    }

    private JkWedge titleWedge() {
        JkWedge w = new JkWedge(icon, title, RichText.empty());
        return warning ? w.variant(JkWedge.Variant.WARNING) : w.variant(JkWedge.Variant.MENU);
    }

    private static int[] naturalWidths(List<Column> cols, List<Row> rows, RenderContext ctx, boolean plain) {
        int n = cols.size();
        int[] w = new int[n];
        for (int i = 0; i < n; i++) {
            w[i] = cellWidth(cols.get(i).name(), ctx, plain);
        }
        for (Row row : rows) {
            if (row.kind() == RowKind.SEPARATOR) continue;
            if (row.kind() == RowKind.SPAN) {
                if (isFullSpan(row, n)) {
                    String text = renderCell(row.cells().getFirst().text(), ctx, plain);
                    int need = RenderContext.visibleWidth(text) + 2;
                    int inner = innerWidth(w);
                    if (need > inner && n > 0) w[n - 1] += need - inner;
                }
                continue;
            }
            for (int i = 0; i < n; i++) {
                String text =
                        i < row.cells().size() ? renderCell(row.cells().get(i).text(), ctx, plain) : "";
                w[i] = Math.max(w[i], RenderContext.visibleWidth(text));
            }
        }
        return w;
    }

    private static int cellWidth(String raw, RenderContext ctx, boolean plain) {
        String s = raw == null ? "" : raw;
        if (plain) s = PlainAscii.transform(s);
        return RenderContext.visibleWidth(s);
    }

    private static String renderCell(RichText text, RenderContext ctx, boolean plain) {
        String s = text == null ? "" : text.render(ctx);
        return plain ? PlainAscii.transform(s) : s;
    }

    /**
     * For each child column, the exclusive parent-column end index (span is {@code
     * prevEnd..end}). Greedy: take the fewest parent columns whose inner width ≥ child natural
     * width; leftover parent columns go to the last child column.
     */
    static int[] snapSpans(int[] parentW, int[] childW) {
        int p = parentW.length;
        int c = childW.length;
        int[] ends = new int[c];
        if (c == 0) return ends;
        int start = 0;
        for (int i = 0; i < c; i++) {
            if (i == c - 1) {
                ends[i] = p;
                break;
            }
            int need = childW[i] + 2;
            int maxJ = Math.max(start + 1, p - (c - i - 1));
            int j = start + 1;
            while (j < maxJ && spanInner(parentW, start, j) < need) {
                j++;
            }
            if (j > maxJ) j = maxJ;
            if (j <= start) j = Math.min(start + 1, p);
            ends[i] = j;
            start = j;
        }
        return ends;
    }

    /** Inner width of parent columns {@code [from, to)}. */
    static int spanInner(int[] w, int from, int to) {
        if (to <= from) return 0;
        int sum = 0;
        for (int i = from; i < to; i++) sum += w[i] + 2;
        sum += (to - from - 1); // internal rails
        return sum;
    }

    private static void expandParentToFit(int[] parentW, int[] ends, int[] childW) {
        int start = 0;
        for (int i = 0; i < ends.length; i++) {
            int end = ends[i];
            int inner = spanInner(parentW, start, end);
            int need = childW[i] + 2;
            if (need > inner && end > start) {
                parentW[end - 1] += need - inner;
            }
            start = end;
        }
    }

    private static int[] snappedChildWidths(int[] parentW, int[] ends) {
        int[] cw = new int[ends.length];
        int start = 0;
        for (int i = 0; i < ends.length; i++) {
            int inner = spanInner(parentW, start, ends[i]);
            cw[i] = Math.max(0, inner - 2);
            start = ends[i];
        }
        return cw;
    }

    private static int innerWidth(int[] w) {
        int inner = 0;
        for (int x : w) inner += x + 2;
        return inner + Math.max(0, w.length - 1);
    }

    private static int minTitleWidth(String title, RenderContext ctx) {
        String name = title == null ? "" : title;
        if (!ctx.ansi()) {
            return JkWedge.plainWedge(Glyphs.MENU_PLAIN, name, null).length() + 3;
        }
        return RenderContext.visibleWidth(name) + 7;
    }

    private static boolean hasVisibleBody(List<Row> rows) {
        for (Row r : rows) {
            if (r.kind() != RowKind.SEPARATOR) return true;
        }
        return false;
    }

    private static int countData(List<Row> rows) {
        int n = 0;
        for (Row r : rows) {
            if (r.kind() == RowKind.DATA) n++;
        }
        return n;
    }

    private static boolean isFullSpan(Row row, int cols) {
        return row.cells().size() == 1 && row.cells().getFirst().colSpan() >= cols;
    }

    private static boolean lastVisibleIsFullSpan(List<Row> rows, int cols) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            Row r = rows.get(i);
            if (r.kind() == RowKind.SEPARATOR) continue;
            return r.kind() == RowKind.SPAN && isFullSpan(r, cols);
        }
        return false;
    }

    private static String flatClose(RenderContext ctx, int inner) {
        boolean ansi = ctx.ansi();
        String s = (ansi ? "╰" : "+") + (ansi ? "─" : "-").repeat(inner) + (ansi ? "╯" : "+");
        return ansi ? Theme.colorize(s, ctx.theme().darkGray()) : s;
    }

    private static String divider(RenderContext ctx, String left, String junction, String right, int[] widths) {
        boolean ansi = ctx.ansi();
        var sb = new StringBuilder(ansi ? left : "+");
        for (int i = 0; i < widths.length; i++) {
            sb.append((ansi ? "─" : "-").repeat(widths[i] + 2));
            sb.append(i == widths.length - 1 ? (ansi ? right : "+") : (ansi ? junction : "+"));
        }
        return ansi ? Theme.colorize(sb.toString(), ctx.theme().darkGray()) : sb.toString();
    }

    /**
     * Join row between parent cols and snapped child cols. Parent rails that continue become
     * {@code ┼}; parent rails that end become {@code ┴}; a child rail with no parent rail is
     * {@code ┬}.
     */
    static String joinDivider(RenderContext ctx, int[] parentW, int[] childEnds) {
        boolean ansi = ctx.ansi();
        // Parent internal rail after column i is at edge i+1. Child rail after child col k
        // is at parent column childEnds[k] (exclusive end) — i.e. the parent rail after
        // column childEnds[k]-1, which is internal iff childEnds[k] < parentW.length.
        boolean[] childRailAfterParentCol = new boolean[parentW.length]; // index i = rail after col i
        for (int k = 0; k < childEnds.length - 1; k++) {
            int end = childEnds[k];
            if (end > 0 && end < parentW.length) {
                childRailAfterParentCol[end - 1] = true;
            }
        }
        var sb = new StringBuilder(ansi ? "├" : "+");
        for (int i = 0; i < parentW.length; i++) {
            sb.append((ansi ? "─" : "-").repeat(parentW[i] + 2));
            if (i == parentW.length - 1) {
                sb.append(ansi ? "┤" : "+");
            } else {
                String junc;
                if (childRailAfterParentCol[i]) {
                    junc = ansi ? "┼" : "+";
                } else {
                    junc = ansi ? "┴" : "+";
                }
                sb.append(junc);
            }
        }
        return ansi ? Theme.colorize(sb.toString(), ctx.theme().darkGray()) : sb.toString();
    }

    private static String headerRow(RenderContext ctx, List<Column> cols, int[] widths, boolean plain) {
        boolean ansi = ctx.ansi();
        String bar = ansi ? Theme.colorize("│", ctx.theme().darkGray()) : "|";
        var sb = new StringBuilder(bar);
        for (int i = 0; i < widths.length; i++) {
            String name = i < cols.size() ? cols.get(i).name() : "";
            if (plain) name = PlainAscii.transform(name);
            String cell = pad(headerCell(name), widths[i], Align.LEFT);
            sb.append(' ').append(cell).append(' ').append(bar);
        }
        return sb.toString();
    }

    private static String dataRow(RenderContext ctx, Row row, List<Column> cols, int[] widths, boolean plain) {
        boolean ansi = ctx.ansi();
        Theme theme = ctx.theme();
        boolean banded = row.highlight() && ansi;
        cc.jumpkick.cli.theme.Rgb band = banded ? theme.darkBlackColor() : null;
        String outerBar = ansi ? Theme.colorize("│", theme.darkGray()) : "|";
        String innerBar = banded ? Theme.colorize("│", theme.withBackground(theme.darkGray(), band)) : outerBar;
        String sp =
                banded ? Theme.colorize(" ", theme.withBackground(org.jline.utils.AttributedStyle.DEFAULT, band)) : " ";
        String leftPad = banded && ctx.nerdfont() ? Theme.colorize(Glyphs.PILL_LEFT_NERD, theme.bright(band)) : sp;
        String rightPad = banded && ctx.nerdfont() ? Theme.colorize(Glyphs.PILL_RIGHT_NERD, theme.bright(band)) : sp;
        var sb = new StringBuilder(outerBar);
        for (int i = 0; i < widths.length; i++) {
            RichText text = i < row.cells().size() ? row.cells().get(i).text() : RichText.empty();
            String raw = renderCell(text, ctx, plain);
            Align align = i < cols.size() ? cols.get(i).align() : Align.LEFT;
            String padL = i == 0 ? leftPad : sp;
            String padR = i == widths.length - 1 ? rightPad : sp;
            String rail = i == widths.length - 1 ? outerBar : innerBar;
            // Alignment fill must carry the band background too, or a banded row shows
            // terminal-background stripes inside every cell shorter than its column.
            sb.append(padL).append(pad(raw, widths[i], align, sp)).append(padR).append(rail);
        }
        return sb.toString();
    }

    private static String spanRow(RenderContext ctx, Row row, int[] widths, boolean plain) {
        boolean ansi = ctx.ansi();
        String bar = ansi ? Theme.colorize("│", ctx.theme().darkGray()) : "|";
        var sb = new StringBuilder(bar);
        int col = 0;
        for (Cell cell : row.cells()) {
            int span = Math.min(cell.colSpan(), widths.length - col);
            if (span < 1) break;
            int inner = spanInner(widths, col, col + span);
            String raw = renderCell(cell.text(), ctx, plain);
            int content = Math.max(0, inner - 2);
            sb.append(' ').append(pad(raw, content, Align.LEFT)).append(' ').append(bar);
            col += span;
        }
        while (col < widths.length) {
            sb.append(' ').append(pad("", widths[col], Align.LEFT)).append(' ').append(bar);
            col++;
        }
        return sb.toString();
    }

    private static String pad(String s, int width, Align align) {
        return pad(s, width, align, " ");
    }

    /** {@code fill} is one visible column (possibly styled, e.g. a band-background space). */
    private static String pad(String s, int width, Align align, String fill) {
        int vis = RenderContext.visibleWidth(s);
        int extra = Math.max(0, width - vis);
        if (align == Align.RIGHT) return fill.repeat(extra) + s;
        if (align == Align.CENTER) {
            int left = extra / 2;
            return fill.repeat(left) + s + fill.repeat(extra - left);
        }
        return s + fill.repeat(extra);
    }
}
