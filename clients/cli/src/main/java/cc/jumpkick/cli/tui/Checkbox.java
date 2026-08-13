// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Theme;
import java.util.List;

/** A checked/unchecked box with a label and optional hint. */
public final class Checkbox implements Widget {

    private final String label;
    private final boolean checked;
    private final boolean focused;
    private final String hint;

    public Checkbox(String label, boolean checked, boolean focused, String hint) {
        this.label = label == null ? "" : label;
        this.checked = checked;
        this.focused = focused;
        this.hint = hint == null ? "" : hint;
    }

    @Override
    public List<String> render(RenderContext ctx) {
        Theme t = ctx.theme();
        String glyph = checked ? Rail.CHECKBOX_ON : Rail.CHECKBOX_OFF;
        if (!ctx.ansi()) {
            // ASCII directly — plain mode's contract is ASCII-only, and widgets rendered outside
            // the CliOutput boundary never pass through PlainAscii.transform (JK-1892).
            String plain = checked ? "[x]" : "[ ]";
            return List.of(plain + " " + label + (hint.isEmpty() ? "" : "  " + hint));
        }
        var glyphStyle = checked ? t.completedStep() : (focused ? t.focused() : t.darkGray());
        var labelStyle = focused ? t.focused() : t.darkGray();
        String line = Theme.colorize(glyph, glyphStyle)
                + "  "
                + Theme.colorize(label, labelStyle)
                + (hint.isEmpty() ? "" : "  " + Theme.colorize(hint, t.darkGray()));
        return List.of(line);
    }
}
