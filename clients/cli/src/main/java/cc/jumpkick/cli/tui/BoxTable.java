// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.GlobalConfig;

/**
 * Shared chrome for box-drawn TUI tables (JDK list, outdated deps, cache info, …).
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
 * fill to the right edge and end in {@code ╮}. Column rules and body rows are still drawn by the
 * caller — only the title chrome is shared.
 */
public final class BoxTable {

    private BoxTable() {}

    /**
     * Title bar for a table whose body rows are {@code totalWidth} visible columns wide (including
     * the outer {@code │} rails — i.e. {@code inner + 2} for the usual box layout where {@code
     * inner} is the span between the rails).
     *
     * <p>ANSI: blue pipeline chip with {@link Glyphs#MENU} + {@code title}, powerline/plain cap,
     * then {@code ─…╮}. No-ANSI: {@code = Title ----+}.
     */
    public static String titleBar(String title, int totalWidth) {
        String name = title == null ? "" : title;
        Theme t = Theme.active();
        boolean nerdfont = GlobalConfig.nerdfont();
        if (!t.isAnsi()) {
            // " = Title >" then dashes to width.
            String head = PipelineWedge.plainWedge(Glyphs.MENU_PLAIN, name, null) + " ";
            int fill = Math.max(1, totalWidth - head.length() - 1);
            return head + "-".repeat(fill) + "+";
        }
        // Always blue (table chrome), never green — unlike success/play wedges.
        // Nerd: body + one trail + PUA; ansi: body + two trails (no PUA).
        String wedge = PipelineWedge.chip(Glyphs.MENU, name, t.pipelineChip(), nerdfont)
                + PipelineWedge.cap(t.planBadgeColor(), nerdfont);
        // " ≡ name " + PUA (nerd) or " ≡ name  " (ansi) — both name.length()+5 visible cols
        int wedgeVisible = name.length() + 5;
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
        int cols = headers.size();
        int[] widths = new int[cols];
        for (int i = 0; i < cols; i++) widths[i] = cell(headers.get(i)).length();
        for (var row : rows) {
            for (int i = 0; i < cols; i++) {
                widths[i] = Math.max(widths[i], cell(i < row.size() ? row.get(i) : "").length());
            }
        }
        int inner = 0;
        for (int w : widths) inner += w + 2;
        inner += cols - 1;

        java.util.List<String> out = new java.util.ArrayList<>();
        out.add(titleBar(title, inner + 2));
        out.add(divider("├", "┬", "┤", widths));
        out.add(row(headers, widths));
        out.add(divider("├", "┼", "┤", widths));
        for (var r : rows) out.add(row(r, widths));
        out.add(divider("╰", "┴", "╯", widths));
        return out;
    }

    private static String cell(String s) {
        return s == null ? "" : s;
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
            sb.append(' ').append(c).append(" ".repeat(widths[i] - c.length())).append(' ').append(bar);
        }
        return sb.toString();
    }
}
