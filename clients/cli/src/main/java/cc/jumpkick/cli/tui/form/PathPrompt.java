// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.form;

import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.terminal.Key;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A directory, typed as a line with Tab completion: one candidate is filled in, several fill their
 * common prefix and are listed under the line. The default shows as a placeholder, as in {@link
 * LinePrompt}. Cooked mode reads the line as it is; there is no Tab on a cooked line.
 */
public final class PathPrompt implements Question<String> {

    /** Candidates listed under the line after an ambiguous Tab. */
    static final int LISTED = 6;

    /** What a partly typed path could become: whole candidates, each ending in the separator. */
    @FunctionalInterface
    public interface Completer {
        List<String> complete(String partial);
    }

    private final String label;
    private final @Nullable String help;
    private final @Nullable String defaultValue;
    private final boolean required;
    private final Completer completer;
    private final StringBuilder typed = new StringBuilder();
    private List<String> listed = List.of();
    private @Nullable String note;

    public PathPrompt(
            String label, @Nullable String help, @Nullable String defaultValue, boolean required, Completer completer) {
        this.label = label;
        this.help = help;
        this.defaultValue = defaultValue;
        this.required = required;
        this.completer = completer;
    }

    /**
     * Completes directories on this machine: a relative path against {@code base}, {@code ~/}
     * against the home directory. Dot-directories are offered only once a dot is typed.
     */
    public static Completer directories(Path base) {
        return partial -> {
            int slash = partial.lastIndexOf('/');
            String parent = slash < 0 ? "" : partial.substring(0, slash + 1);
            String prefix = partial.substring(slash + 1);
            Path dir = parent.startsWith("~/") || parent.equals("~")
                    ? PathUtil.userHome().resolve(parent.substring(Math.min(2, parent.length())))
                    : base.resolve(parent);
            List<String> out = new ArrayList<>();
            try {
                PathUtil.forEachChild(dir, (path, attrs) -> {
                    String name = String.valueOf(path.getFileName());
                    boolean shown = !name.startsWith(".") || prefix.startsWith(".");
                    if (attrs.isDirectory() && shown && name.startsWith(prefix)) out.add(parent + name + "/");
                    return true;
                });
            } catch (IOException unreadable) {
                return List.of();
            }
            out.sort(null);
            return out;
        };
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
        if (!listed.isEmpty()) {
            List<String> shown = listed.subList(0, Math.min(LISTED, listed.size()));
            String more = listed.size() > LISTED ? "  … +" + (listed.size() - LISTED) : "";
            rows.add(Rows.hint(ctx, String.join("  ", shown) + more));
        }
        if (note != null) rows.add(Rows.error(ctx, note));
        return rows;
    }

    @Override
    public Reply<String> key(Key key) {
        listed = List.of();
        note = null;
        switch (key) {
            case Key.Tab t -> complete();
            case Key.Enter e -> {
                Reply<String> reply = line(typed.toString());
                if (reply instanceof Retry<String> retry) note = retry.lines().getFirst();
                return reply instanceof Done<String> ? reply : new Pending<>();
            }
            case Key.Escape e -> {
                return new Cancelled<>();
            }
            case Key.Backspace b -> {
                if (!typed.isEmpty()) typed.deleteCharAt(typed.length() - 1);
            }
            case Key.Right r -> {
                if (typed.isEmpty() && defaultValue != null) typed.append(defaultValue);
            }
            case Key.Space s -> typed.append(' ');
            case Key.Char c -> typed.append(c.c());
            default -> {}
        }
        return new Pending<>();
    }

    private void complete() {
        List<String> candidates = completer.complete(typed.toString());
        if (candidates.isEmpty()) {
            note = "No directory starts with '" + typed + "'.";
            return;
        }
        String common = candidates.getFirst();
        for (String c : candidates) {
            int n = 0;
            while (n < common.length() && n < c.length() && common.charAt(n) == c.charAt(n)) n++;
            common = common.substring(0, n);
        }
        typed.setLength(0);
        typed.append(common);
        if (candidates.size() > 1) listed = candidates;
    }

    @Override
    public List<String> menu(RenderContext ctx) {
        return List.of();
    }

    @Override
    public String cue() {
        return defaultValue == null ? "" : "[" + defaultValue + "]";
    }

    @Override
    public Reply<String> line(String line) {
        String value = line.isEmpty() && defaultValue != null ? defaultValue : line;
        if (value.isEmpty() && required) return new Retry<>(List.of("A directory is needed."));
        return new Done<>(value);
    }

    @Override
    public String settled(String value) {
        return value.isEmpty() ? "(none)" : value;
    }
}
