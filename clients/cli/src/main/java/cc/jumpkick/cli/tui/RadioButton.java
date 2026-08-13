// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Theme;
import java.util.List;

/** One radio option: selected/unselected glyph + label + optional hint. */
public final class RadioButton implements Widget {

    private final String label;
    private final boolean selected;
    private final boolean focused;
    private final String hint;

    public RadioButton(String label, boolean selected, boolean focused, String hint) {
        this.label = label == null ? "" : label;
        this.selected = selected;
        this.focused = focused;
        this.hint = hint == null ? "" : hint;
    }

    public String renderInline(RenderContext ctx) {
        Theme t = ctx.theme();
        String glyph = selected || focused ? Rail.RADIO_ON : Rail.RADIO_OFF;
        if (!ctx.ansi()) {
            // ASCII directly — plain mode's contract is ASCII-only, and widgets rendered outside
            // the CliOutput boundary never pass through PlainAscii.transform (JK-1892).
            String plain = selected || focused ? "(*)" : "( )";
            return plain + " " + label + (hint.isEmpty() ? "" : "  " + hint);
        }
        var glyphStyle = (selected || focused) ? t.completedStep() : t.darkGray();
        var labelStyle = focused ? t.focused() : t.darkGray();
        return Theme.colorize(glyph, glyphStyle)
                + "  "
                + Theme.colorize(label, labelStyle)
                + (hint.isEmpty() ? "" : "  " + Theme.colorize(hint, t.darkGray()));
    }

    @Override
    public List<String> render(RenderContext ctx) {
        return List.of(renderInline(ctx));
    }
}
