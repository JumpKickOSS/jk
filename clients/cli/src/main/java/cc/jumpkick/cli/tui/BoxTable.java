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
        if (!t.isAnsi()) {
            String head = "= " + name + " ";
            int fill = Math.max(1, totalWidth - head.length() - 1);
            return head + "-".repeat(fill) + "+";
        }
        boolean nerdfont = GlobalConfig.nerdfont();
        // Always blue (table chrome), never green — unlike success/play wedges.
        String wedge = PipelineWedge.chip(Glyphs.MENU, name, t.pipelineChip())
                + PipelineWedge.cap(t.planBadgeColor(), nerdfont);
        // Visible chip: " " + glyph + " " + name + " " → name.length() + 4; cap → +1.
        int wedgeVisible = name.length() + 5;
        int fill = Math.max(1, totalWidth - wedgeVisible - 1); // -1 for the closing ╮
        return wedge + Theme.colorize("─".repeat(fill) + "╮", t.darkGray());
    }
}
