// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.cli.Ansi;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.theme.Theme;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;

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
        try (Terminal terminal = Wizard.openTerminal()) {
            Wizard.drainInput(terminal.reader(), 40L);
            return ask(terminal);
        } catch (IOException e) {
            return cookedFallback();
        }
    }

    public T ask(Terminal terminal) {
        if (assumeYes() && onEnter instanceof Boolean) {
            @SuppressWarnings("unchecked")
            T yes = (T) Boolean.TRUE;
            return yes;
        }
        if (!Theme.active().isAnsi()) return cookedFallback();
        var err = CliOutput.stderr();
        String hint = hintText(false);
        err.print(promptText(false));
        err.flush();
        Attributes saved = terminal.enterRawMode();
        try {
            NonBlockingReader reader = terminal.reader();
            while (true) {
                T result = interpret(KeyReader.read(reader));
                if (result != null) {
                    err.print(settleOverwrite(hint, settleLabel(result)));
                    err.flush();
                    return result;
                }
            }
        } finally {
            Wizard.restoreCooked(terminal, saved);
        }
    }

    /**
     * Rewinds over the printed hint plus its trailing space and paints the settled answer. The
     * hint carries SGR color when ANSI is active, so the rewind must count visible columns, not
     * raw chars — overshooting drags the cursor into the question text and the erase wipes it.
     */
    static String settleOverwrite(String hint, String answer) {
        return Ansi.cursorBack(RenderContext.visibleWidth(hint) + 1) + answer + Ansi.ERASE_LINE_TO_END + "\r\n";
    }

    @Override
    public List<String> render(RenderContext ctx) {
        return List.of(question.render(ctx) + " " + hintText(ctx.mode() == RenderContext.Mode.PLAIN));
    }

    private T interpret(KeyReader.Key key) {
        return switch (key) {
            case KeyReader.Key.Char c -> {
                char ch = Character.toLowerCase(c.c());
                for (Binding<T> b : bindings) {
                    if (Character.toLowerCase(b.key()) == ch) yield b.value();
                }
                yield null;
            }
            case KeyReader.Key.Enter ignored -> onEnter;
            case KeyReader.Key.CtrlC ignored -> onCancel;
            case KeyReader.Key.Escape ignored -> onCancel;
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

    private String settleLabel(T result) {
        for (Binding<T> b : bindings) {
            if (java.util.Objects.equals(b.value(), result)) {
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
            if (java.util.Objects.equals(b.value(), onEnter)) return b.key();
        }
        return bindings.isEmpty() ? '?' : bindings.getFirst().key();
    }
}
