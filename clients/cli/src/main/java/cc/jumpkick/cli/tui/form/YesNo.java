// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.form;

import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.terminal.Key;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A yes/no confirm. {@code y} and {@code n} answer at once; ← / → move between the two and Enter
 * takes the focused one, which starts on the default (or on No when there is none).
 */
public final class YesNo implements Question<Boolean> {

    private final String label;
    private final @Nullable String help;
    private final @Nullable Boolean defaultValue;
    private boolean focusYes;

    public YesNo(String label, @Nullable String help, @Nullable Boolean defaultValue) {
        this.label = label;
        this.help = help;
        this.defaultValue = defaultValue;
        this.focusYes = Boolean.TRUE.equals(defaultValue);
    }

    /** Whether Yes holds the focus now; Enter would answer it. */
    boolean focusYes() {
        return focusYes;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public @Nullable String help() {
        return help;
    }

    @Override
    public List<String> body(RenderContext ctx) {
        return List.of(Rows.INDENT + Rows.segment(ctx, "Yes", focusYes) + " " + Rows.segment(ctx, "No", !focusYes));
    }

    @Override
    public Reply<Boolean> key(Key key) {
        return switch (key) {
            case Key.Char c when Character.toLowerCase(c.c()) == 'y' -> new Done<>(true);
            case Key.Char c when Character.toLowerCase(c.c()) == 'n' -> new Done<>(false);
            case Key.Left l -> move();
            case Key.Right r -> move();
            case Key.Tab t -> move();
            case Key.Enter e -> new Done<>(focusYes);
            case Key.Escape e -> new Cancelled<>();
            default -> new Pending<>();
        };
    }

    private Reply<Boolean> move() {
        focusYes = !focusYes;
        return new Pending<>();
    }

    @Override
    public List<String> menu(RenderContext ctx) {
        return List.of();
    }

    @Override
    public String cue() {
        if (defaultValue == null) return "[y/n]";
        return defaultValue ? "[Y/n]" : "[y/N]";
    }

    @Override
    public Reply<Boolean> line(String line) {
        return switch (line.toLowerCase(Locale.ROOT)) {
            case "y", "yes" -> new Done<>(true);
            case "n", "no" -> new Done<>(false);
            case "" -> defaultValue == null ? new Retry<>(List.of("Answer y or n.")) : new Done<>(defaultValue);
            default -> new Retry<>(List.of("Answer y or n."));
        };
    }

    @Override
    public String settled(Boolean value) {
        return value ? "Yes" : "No";
    }
}
