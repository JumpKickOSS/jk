// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.GlobalConfig;

/**
 * Shared chrome for box-drawn TUI tables (JDK list, outdated deps, repo storage, …).
 *
 * <p>Title line (replaces the old full-width blue banner under a {@code ╭──╮} top border):
 *
 * <pre>
 *  ≡ Installed OpenJDKs  ──────────────────────────────────────╮
 * ├─────────┬─────────────────┬─────────────────┬──────────────┤
 * │ Version │ Vendor          │ Spec            │ …
 * </pre>
 *
 * A blue {@link CommandWedge}-style chip (menu glyph + title) on the left; dark-gray box dashes
 * fill to the right edge and end in {@code ╮}. Column headers are italic ({@link #headerCell});
 * body rows are drawn by the caller or by {@link #render}.
 */
public final class BoxTable {

    private BoxTable() {}

    /**
     * Style a column-header cell: italic when ANSI is on, plain otherwise. Prefer {@link #render}
     * (applies this automatically); use for custom tables that build their own header row.
     */
    public static String headerCell(String text) {
        String s = text == null ? "" : text;
        if (s.isEmpty() || !Theme.active().isAnsi()) return s;
        return Theme.colorize(s, org.jline.utils.AttributedStyle.DEFAULT.italic());
    }

    /**
     * Title bar for a table whose body rows are {@code totalWidth} visible columns wide (including
     * the outer {@code │} rails — i.e. {@code inner + 2} for the usual box layout where {@code
     * inner} is the span between the rails).
     *
     * <p>ANSI: blue pipeline chip with {@link Glyphs#MENU} + {@code title}, powerline/plain cap,
     * then {@code ─…╮}. No-ANSI: {@code = Title ----+}.
     */
    public static String titleBar(String title, int totalWidth) {
        return titleBar(title, totalWidth, false);
    }

    /**
     * Like {@link #titleBar(String, int)} but with a <strong>yellow warning</strong> chip and
     * {@link Glyphs#BANG} (e.g. destructive confirm tables).
     */
    public static String titleBarWarning(String title, int totalWidth) {
        return titleBar(title, totalWidth, true);
    }

    private static String titleBar(String title, int totalWidth, boolean warning) {
        String name = title == null ? "" : title;
        Theme t = Theme.active();
        boolean nerdfont = GlobalConfig.nerdfont();
        String glyph = warning ? Glyphs.BANG : Glyphs.MENU;
        String plainGlyph = warning ? Glyphs.BANG_PLAIN : Glyphs.MENU_PLAIN;
        if (!t.isAnsi()) {
            String head = BuildPlanWedge.plainWedge(plainGlyph, name, null) + " ";
            int fill = Math.max(1, totalWidth - head.length() - 1);
            return head + "-".repeat(fill) + "+";
        }
        // Warning: black (#000) on amber — white-on-yellow fails contrast; otherwise blue chip.
        org.jline.utils.AttributedStyle chipStyle;
        cc.jumpkick.cli.theme.Rgb capColor;
        if (warning) {
            capColor = cc.jumpkick.cli.theme.JkDarkTheme.NORMAL_YELLOW;
            chipStyle = t.withBackground(t.bright(0, 0, 0), capColor);
        } else {
            chipStyle = t.pipelineChip();
            capColor = t.planBadgeColor();
        }
        String wedge = BuildPlanWedge.chip(glyph, name, chipStyle, nerdfont) + BuildPlanWedge.cap(capColor, nerdfont);
        // " ≡/‼ name " + PUA (nerd) or " ≡/‼ name  " (ansi) — name columns + 5 visible cols
        int wedgeVisible = visibleWidth(name) + 5;
        int fill = Math.max(1, totalWidth - wedgeVisible - 1); // -1 for the closing ╮
        return wedge + Theme.colorize("─".repeat(fill) + "╮", t.darkGray());
    }

    /**
     * Whole table: title bar, header row, data rows, closing border — the standard list-command
     * look (JK-1375). Column widths fit the widest cell; ANSI rails/dividers are dark gray, no-ANSI
     * degrades to {@code + - |} ASCII. Rows shorter than {@code headers} are right-padded with
     * empty cells; longer rows are truncated to the header count.
     */
    public static java.util.List<String> render(
            String title, java.util.List<String> headers, java.util.List<? extends java.util.List<String>> rows) {
        return render(title, headers, rows, false, false);
    }

    /**
     * Warning-styled table (yellow ‼ title chip) with a divider between every data row — for
     * destructive confirmations.
     */
    public static java.util.List<String> renderWarning(
            String title, java.util.List<String> headers, java.util.List<? extends java.util.List<String>> rows) {
        return render(title, headers, rows, true, true);
    }

    private static java.util.List<String> render(
            String title,
            java.util.List<String> headers,
            java.util.List<? extends java.util.List<String>> rows,
            boolean warning,
            boolean rowSeparators) {
        // Plain mode expands some glyphs at the print boundary (… → ..., — → --, □ → [ ]);
        // transform cells up front so width accounting matches what actually prints.
        boolean plain = !Theme.active().isAnsi();
        int cols = headers.size();
        java.util.List<String> heads = new java.util.ArrayList<>(cols);
        for (String h : headers) heads.add(plain ? PlainAscii.transform(cell(h)) : cell(h));
        java.util.List<java.util.List<String>> cells = new java.util.ArrayList<>(rows.size());
        for (var row : rows) {
            java.util.List<String> r = new java.util.ArrayList<>(cols);
            for (int i = 0; i < cols; i++) {
                String c = cell(i < row.size() ? row.get(i) : "");
                r.add(plain ? PlainAscii.transform(c) : c);
            }
            cells.add(r);
        }
        int[] widths = new int[cols];
        for (int i = 0; i < cols; i++) widths[i] = visibleWidth(heads.get(i));
        for (var r : cells) {
            for (int i = 0; i < cols; i++) widths[i] = Math.max(widths[i], visibleWidth(r.get(i)));
        }
        int inner = 0;
        for (int w : widths) inner += w + 2;
        inner += cols - 1;
        // A title chip wider than the body would overhang the right rail; widen the last
        // column so the top border always closes on the rail.
        int deficit = minTitleWidth(title, plain) - (inner + 2);
        if (deficit > 0 && cols > 0) {
            widths[cols - 1] += deficit;
            inner += deficit;
        }

        java.util.List<String> out = new java.util.ArrayList<>();
        out.add(warning ? titleBarWarning(title, inner + 2) : titleBar(title, inner + 2));
        out.add(divider("├", "┬", "┤", widths));
        java.util.List<String> styledHeaders = new java.util.ArrayList<>(cols);
        for (String h : heads) styledHeaders.add(headerCell(h));
        out.add(row(styledHeaders, widths));
        if (!cells.isEmpty()) out.add(divider("├", "┼", "┤", widths));
        for (int i = 0; i < cells.size(); i++) {
            out.add(row(cells.get(i), widths));
            if (rowSeparators && i < cells.size() - 1) {
                out.add(divider("├", "┼", "┤", widths));
            }
        }
        out.add(divider("╰", "┴", "╯", widths));
        return out;
    }

    /**
     * Smallest table width (rails included) whose title bar still closes on the right rail:
     * the wedge, at least one fill dash, and the closing corner. Both plain glyphs ({@code =}
     * / {@code !}) are one column, so the plain accounting holds for warning chips too.
     */
    private static int minTitleWidth(String title, boolean plain) {
        String name = title == null ? "" : title;
        if (plain) {
            // " = Title > " + at least one '-' + closing '+'
            return BuildPlanWedge.plainWedge(Glyphs.MENU_PLAIN, name, null).length() + 3;
        }
        return visibleWidth(name) + 7; // chip (name + 5 cols) + one '─' + '╮'
    }

    private static String cell(String s) {
        return s == null ? "" : s;
    }

    /**
     * Visible terminal-column width: CSI/OSC sequences are ignored and each code point counts
     * its wcwidth (CJK wide chars are 2 columns, astral chars 1 code point each) so colored and
     * non-ASCII cells pad correctly. Public so custom tables that share this chrome (e.g. {@code
     * jk repo storage}'s spanning utilization footer) pad with the same rule instead of raw
     * {@code String.length()}.
     */
    public static int visibleWidth(String s) {
        if (s == null || s.isEmpty()) return 0;
        return new org.jline.utils.AttributedString(org.jline.utils.AttributedString.stripAnsi(s)).columnLength();
    }

    private static String divider(String left, String junction, String right, int[] widths) {
        boolean ansi = Theme.active().isAnsi();
        var sb = new StringBuilder(ansi ? left : "+");
        for (int i = 0; i < widths.length; i++) {
            sb.append((ansi ? "─" : "-").repeat(widths[i] + 2));
            sb.append(i == widths.length - 1 ? (ansi ? right : "+") : (ansi ? junction : "+"));
        }
        return ansi ? Theme.colorize(sb.toString(), Theme.active().darkGray()) : sb.toString();
    }

    private static String row(java.util.List<String> cells, int[] widths) {
        boolean ansi = Theme.active().isAnsi();
        String bar = ansi ? Theme.colorize("│", Theme.active().darkGray()) : "|";
        var sb = new StringBuilder(bar);
        for (int i = 0; i < widths.length; i++) {
            String c = cell(i < cells.size() ? cells.get(i) : "");
            int pad = Math.max(0, widths[i] - visibleWidth(c));
            sb.append(' ').append(c).append(" ".repeat(pad)).append(' ').append(bar);
        }
        return sb.toString();
    }
}
