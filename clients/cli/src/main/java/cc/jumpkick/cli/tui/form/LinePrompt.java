// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.form;

import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.terminal.Key;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A line of text, checked against a pattern. The default shows as a placeholder: Enter on an empty
 * line takes it, and Tab or → types it in for editing. A required prompt refuses an empty answer.
 */
public final class LinePrompt implements Question<String> {

    private final String label;
    private final @Nullable String help;
    private final @Nullable String defaultValue;
    private final @Nullable Pattern pattern;
    private final boolean required;
    private final StringBuilder typed = new StringBuilder();
    private @Nullable String error;

    public LinePrompt(
            String label,
            @Nullable String help,
            @Nullable String defaultValue,
            @Nullable String pattern,
            boolean required) {
        this.label = label;
        this.help = help;
        this.defaultValue = defaultValue;
        this.pattern = pattern == null ? null : Pattern.compile(pattern);
        this.required = required;
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
        List<String> rows = new ArrayList<>();
        rows.add(Rows.input(ctx, typed.toString(), defaultValue));
        if (error != null) rows.add(Rows.error(ctx, error));
        return rows;
    }

    @Override
    public Reply<String> key(Key key) {
        switch (key) {
            case Key.Enter e -> {
                Reply<String> reply = accept(typed.toString());
                if (reply instanceof Retry<String> retry) {
                    error = retry.lines().getFirst();
                    return new Pending<>();
                }
                return reply;
            }
            case Key.Escape e -> {
                return new Cancelled<>();
            }
            case Key.Backspace b -> {
                if (!typed.isEmpty()) typed.deleteCharAt(typed.length() - 1);
            }
            case Key.Tab t -> fillDefault();
            case Key.Right r -> fillDefault();
            case Key.Space s -> typed.append(' ');
            case Key.Char c -> typed.append(c.c());
            default -> {
                return new Pending<>();
            }
        }
        error = null;
        return new Pending<>();
    }

    private void fillDefault() {
        if (typed.isEmpty() && defaultValue != null) typed.append(defaultValue);
    }

    @Override
    public List<String> menu(RenderContext ctx) {
        return List.of();
    }

    @Override
    public String cue() {
        return defaultValue == null || defaultValue.isEmpty() ? "" : "[" + defaultValue + "]";
    }

    @Override
    public Reply<String> line(String line) {
        return accept(line);
    }

    private Reply<String> accept(String text) {
        String value = text.isEmpty() && defaultValue != null ? defaultValue : text;
        if (value.isEmpty()) {
            return required ? new Retry<>(List.of("A value is needed.")) : new Done<>(value);
        }
        if (pattern != null && !pattern.matcher(value).matches()) {
            return new Retry<>(List.of("'" + value + "' does not match " + pattern.pattern() + "."));
        }
        return new Done<>(value);
    }

    @Override
    public String settled(String value) {
        return value.isEmpty() ? "(none)" : value;
    }
}
