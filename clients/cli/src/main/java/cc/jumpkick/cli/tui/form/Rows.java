// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.form;

import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Pill;
import cc.jumpkick.cli.tui.Rail;
import cc.jumpkick.cli.tui.RenderContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The pieces every question paints with, so colour means one thing across them: the focused row in
 * the focus colour, a pick in the completed green, an error in red, a hint in dark gray.
 */
final class Rows {

    /** Indent of a body row under the label's bullet. */
    static final String INDENT = "   ";

    private Rows() {}

    /** {@code ■  Label  help}: the active question's first row. */
    static String header(RenderContext ctx, String label, @Nullable String help) {
        Theme t = ctx.theme();
        String row = Theme.paint(Rail.CHECKBOX_ON, t.activeStep()) + "  " + Theme.paint(label, t.focused());
        return help == null ? row : row + "  " + Theme.paint(help, t.darkGray());
    }

    /** {@code □  Label ➜ answer}: a settled question, left in the transcript. */
    static String settled(RenderContext ctx, String label, String answer) {
        Theme t = ctx.theme();
        return Theme.paint(Rail.CHECKBOX_OFF, t.completedStep())
                + "  "
                + Theme.paint(label, t.completedPrompt())
                + " "
                + Theme.paint("➜", t.brightGreen())
                + " "
                + Theme.paint(answer, t.settled().italic());
    }

    /** The typed text and a caret after it; the placeholder in dim italic while nothing is typed. */
    static String input(RenderContext ctx, String typed, @Nullable String placeholder) {
        Theme t = ctx.theme();
        String caret = Theme.paint("▏", t.activeStep());
        if (typed.isEmpty() && placeholder != null && !placeholder.isEmpty()) {
            return INDENT + caret + Theme.paint(placeholder, t.darkGray().italic());
        }
        return INDENT + Theme.paint(typed, t.focused()) + caret;
    }

    static String error(RenderContext ctx, String message) {
        return INDENT + Theme.paint(message, ctx.theme().error());
    }

    static String hint(RenderContext ctx, String text) {
        return INDENT + Theme.paint(text, ctx.theme().darkGray());
    }

    /** One segment of an inline choice: the focused one a pill, the others padded dark gray. */
    static String segment(RenderContext ctx, String text, boolean focused) {
        if (focused) return Objects.requireNonNullElse(Pill.branded(text).renderInline(ctx), text);
        return Theme.paint(" " + text + " ", ctx.theme().darkGray());
    }

    /** A numbered cooked menu row: {@code   1) body}. */
    static List<String> numbered(List<String> bodies) {
        List<String> out = new ArrayList<>(bodies.size());
        for (int i = 0; i < bodies.size(); i++) out.add("  " + (i + 1) + ") " + bodies.get(i));
        return out;
    }

    /** The window of {@code size} rows to show out of {@code total} so that {@code focus} is in it. */
    static int windowStart(int focus, int total, int size) {
        if (total <= size) return 0;
        return Math.max(0, Math.min(focus - size / 2, total - size));
    }
}
