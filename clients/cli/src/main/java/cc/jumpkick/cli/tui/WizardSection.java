// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import java.util.ArrayList;
import java.util.List;

/**
 * A titled block of widgets under a {@link JkWedge} (or a plain heading). Used by {@link Wizard}
 * and reusable anywhere a labeled group of inputs is needed.
 */
public final class WizardSection implements Widget {

    private final String title;
    private final RichText subtitle;
    private final List<Widget> body;

    public WizardSection(String title, RichText subtitle, List<Widget> body) {
        this.title = title == null ? "" : title;
        this.subtitle = subtitle == null ? RichText.empty() : subtitle;
        this.body = body == null ? List.of() : List.copyOf(body);
    }

    public static WizardSection of(String title, Widget... body) {
        return new WizardSection(title, RichText.empty(), List.of(body));
    }

    @Override
    public List<String> render(RenderContext ctx) {
        var out = new ArrayList<String>();
        out.add(new JkWedge(Icon.menu(), title, subtitle)
                .variant(JkWedge.Variant.MENU)
                .renderLine(ctx));
        for (Widget w : body) {
            out.addAll(w.render(ctx));
        }
        return out;
    }
}
