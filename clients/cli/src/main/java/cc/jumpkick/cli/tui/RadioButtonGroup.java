// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import java.util.ArrayList;
import java.util.List;

/** A set of {@link RadioButton}s, laid out vertically or horizontally. */
public final class RadioButtonGroup implements Widget {

    private final List<RadioButton> buttons;
    private final Orientation orientation;

    public RadioButtonGroup(List<RadioButton> buttons, Orientation orientation) {
        this.buttons = buttons == null ? List.of() : List.copyOf(buttons);
        this.orientation = orientation == null ? Orientation.VERTICAL : orientation;
    }

    @Override
    public List<String> render(RenderContext ctx) {
        if (orientation == Orientation.HORIZONTAL) {
            var sb = new StringBuilder();
            for (int i = 0; i < buttons.size(); i++) {
                if (i > 0) sb.append("  ");
                sb.append(buttons.get(i).renderInline(ctx));
            }
            return List.of(sb.toString());
        }
        var lines = new ArrayList<String>();
        for (RadioButton b : buttons) {
            lines.addAll(b.render(ctx));
        }
        return lines;
    }
}
