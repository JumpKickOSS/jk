// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import java.util.List;
import org.jline.terminal.Terminal;

/**
 * Yes/no prompt. Default-yes paints {@code [Y/n]}; default-no paints {@code [y/N]}. Enter takes the
 * default; Esc / Ctrl-C / EOF decline.
 */
public final class Confirmation implements Widget {

    private final Prompt<Boolean> prompt;

    private Confirmation(Prompt<Boolean> prompt) {
        this.prompt = prompt;
    }

    public static Confirmation of(String question, boolean defaultYes) {
        return of(RichText.plain(question == null ? "" : question), defaultYes);
    }

    public static Confirmation of(RichText question, boolean defaultYes) {
        var prompt = new Prompt<>(
                question,
                List.of(new Prompt.Binding<>('y', "Yes", true), new Prompt.Binding<>('n', "No", false)),
                defaultYes,
                false);
        return new Confirmation(prompt);
    }

    public boolean ask() {
        return Boolean.TRUE.equals(prompt.ask());
    }

    public boolean ask(Terminal terminal) {
        return Boolean.TRUE.equals(prompt.ask(terminal));
    }

    @Override
    public List<String> render(RenderContext ctx) {
        return prompt.render(ctx);
    }
}
