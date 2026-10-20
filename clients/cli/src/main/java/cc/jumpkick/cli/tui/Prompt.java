// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.terminal.InputMode;
import cc.jumpkick.terminal.Key;
import cc.jumpkick.terminal.ModeGuard;
import cc.jumpkick.terminal.RegionPainter;
import cc.jumpkick.terminal.TerminalSession;
import cc.jumpkick.terminal.Terminals;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Single-keystroke prompt. Bindings map a key to a value and a settle label. Enter takes the
 * default; Esc / Ctrl-C takes {@code onCancel}.
 */
public final class Prompt<T> implements Widget {

    public record Binding<T>(char key, String label, T value) {}

    private static final ThreadLocal<Boolean> ASSUME_YES = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final RichText question;
    private final List<Binding<T>> bindings;
    private final T onEnter;
    private final T onCancel;
    private final char defaultKey;

    public Prompt(RichText question, List<Binding<T>> bindings, T onEnter, T onCancel) {
        this.question = question == null ? RichText.empty() : question;
        this.bindings = List.copyOf(bindings);
        this.onEnter = onEnter;
        this.onCancel = onCancel;
        this.defaultKey = defaultKeyOf(this.bindings, onEnter);
    }

    public static void setAssumeYes(boolean yes) {
        ASSUME_YES.set(yes);
    }

    public static void clearAssumeYes() {
        ASSUME_YES.remove();
    }

    public static boolean assumeYes() {
        return Boolean.TRUE.equals(ASSUME_YES.get());
    }

    static boolean rawEligible(boolean canPrompt, boolean ansi) {
        return canPrompt && ansi;
    }

    public T ask() {
        if (assumeYes() && onEnter instanceof Boolean) {
            @SuppressWarnings("unchecked")
            T yes = (T) Boolean.TRUE;
            return yes;
        }
        if (!rawEligible(Interactivity.canPrompt(), Theme.active().isAnsi())) {
            return cookedFallback();
        }
        TerminalSession tty = Terminals.controlling();
        if (!tty.isLive()) {
            return cookedFallback();
        }
        tty.drain(Duration.ofMillis(40));
        return ask(tty);
    }

    public T ask(TerminalSession tty) {
        if (assumeYes() && onEnter instanceof Boolean) {
            @SuppressWarnings("unchecked")
            T yes = (T) Boolean.TRUE;
            return yes;
        }
        if (!Theme.active().isAnsi()) return cookedFallback();
        return ask(tty, CliOutput.stderr());
    }

    /** The raw-mode prompt on {@code tty}, painted to {@code err} as one in-place row. */
    T ask(TerminalSession tty, PrintStream err) {
        var painter = new RegionPainter(RegionPainter.Park.IN_ROW);
        int cols = tty.size().cols();
        String asked = question.render() + " ";
        StringBuilder frame = new StringBuilder();
        painter.paint(frame, List.of(asked + hintText(false)), cols, 0, 0);
        err.print(frame.append(' '));
        err.flush();
        try (ModeGuard raw = tty.enter(InputMode.PROMPT)) {
            while (true) {
                var key = tty.readKey(Duration.ZERO);
                if (key.isEmpty()) {
                    return onCancel;
                }
                T result = interpret(key.get());
                if (result != null) {
                    frame.setLength(0);
                    painter.paint(frame, List.of(asked + settleLabel(result)), cols, 0, 0);
                    err.print(frame.append("\r\n"));
                    err.flush();
                    return result;
                }
            }
        }
    }

    @Override
    public List<String> render(RenderContext ctx) {
        return List.of(question.render(ctx) + " " + hintText(ctx.mode() == RenderContext.Mode.PLAIN));
    }

    private @Nullable T interpret(Key key) {
        return switch (key) {
            case Key.Char c -> {
                char ch = Character.toLowerCase(c.c());
                for (Binding<T> b : bindings) {
                    if (Character.toLowerCase(b.key()) == ch) yield b.value();
                }
                yield null;
            }
            case Key.Enter ignored -> onEnter;
            case Key.CtrlC ignored -> onCancel;
            case Key.Escape ignored -> onCancel;
            default -> null;
        };
    }

    private T cookedFallback() {
        var err = CliOutput.stderr();
        err.print(promptText(true));
        err.flush();
        T result;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null) {
                result = onCancel;
            } else {
                String t = line.trim();
                result = t.isEmpty() ? onEnter : matchCooked(t);
            }
        } catch (IOException e) {
            result = onCancel;
        }
        err.println(settleLabel(result));
        err.flush();
        return result;
    }

    private T matchCooked(String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        for (Binding<T> b : bindings) {
            if (lower.equals(String.valueOf(b.key()).toLowerCase(Locale.ROOT))
                    || lower.equals(b.label().toLowerCase(Locale.ROOT))) {
                return b.value();
            }
        }
        return onCancel;
    }

    private String promptText(boolean plain) {
        return question.render() + " " + hintText(plain) + " ";
    }

    private String hintText(boolean plain) {
        var dim = Theme.active().darkGray();
        var sb = new StringBuilder();
        if (plain) {
            sb.append('[');
            for (int i = 0; i < bindings.size(); i++) {
                if (i > 0) sb.append('/');
                Binding<T> b = bindings.get(i);
                char k = b.key();
                sb.append(k == defaultKey ? Character.toUpperCase(k) : Character.toLowerCase(k));
            }
            sb.append(']');
            return sb.toString();
        }
        sb.append(Theme.colorize("[", dim));
        for (int i = 0; i < bindings.size(); i++) {
            if (i > 0) sb.append(Theme.colorize("/", dim));
            Binding<T> b = bindings.get(i);
            char k = b.key();
            sb.append(k == defaultKey ? Character.toUpperCase(k) : Character.toLowerCase(k));
        }
        sb.append(Theme.colorize("]", dim));
        return sb.toString();
    }

    private @Nullable String settleLabel(T result) {
        for (Binding<T> b : bindings) {
            if (Objects.equals(b.value(), result)) {
                boolean ok = result instanceof Boolean bool && bool;
                var style = ok ? Theme.active().success() : Theme.active().error();
                if (result instanceof Boolean) {
                    return Theme.colorize(b.label(), style);
                }
                return Theme.colorize(b.label(), Theme.active().focused());
            }
        }
        return result == null ? "" : String.valueOf(result);
    }

    private static <T> char defaultKeyOf(List<Binding<T>> bindings, T onEnter) {
        for (Binding<T> b : bindings) {
            if (Objects.equals(b.value(), onEnter)) return b.key();
        }
        return bindings.isEmpty() ? '?' : bindings.getFirst().key();
    }
}
