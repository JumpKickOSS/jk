// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.theme.Theme;
import java.util.ArrayList;
import java.util.List;

/** Single-line text field: placeholder when empty, focused value otherwise, optional error. */
public final class TextInput implements Widget {

    private final String value;
    private final String placeholder;
    private final boolean focused;
    private final String error;

    public TextInput(String value, String placeholder, boolean focused, String error) {
        this.value = value == null ? "" : value;
        this.placeholder = placeholder == null ? "" : placeholder;
        this.focused = focused;
        this.error = error == null ? "" : error;
    }

    public static TextInput of(String value) {
        return new TextInput(value, "", true, "");
    }

    public String value() {
        return value;
    }

    @Override
    public List<String> render(RenderContext ctx) {
        Theme t = ctx.theme();
        String body;
        if (value.isEmpty()) {
            body = placeholder.isEmpty()
                    ? ""
                    : (ctx.ansi() ? Theme.colorize(placeholder, t.darkGray().italic()) : placeholder);
        } else {
            body = ctx.ansi() ? Theme.colorize(value, focused ? t.focused() : t.darkGray()) : value;
        }
        var out = new ArrayList<String>();
        out.add(body);
        if (!error.isEmpty()) {
            out.add(ctx.ansi() ? Theme.colorize(error, t.error()) : error);
        }
        return out;
    }
}
