// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.form;

import cc.jumpkick.cli.tui.RadioButton;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.form.Option;
import cc.jumpkick.terminal.Key;
import cc.jumpkick.terminal.Width;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One option of a list. A short list that fits the width is one inline row of segments, moved
 * with ← / →, the focused one a pill; a longer one is a vertical list moved with ↑ / ↓, scrolled
 * {@value #ROWS} rows at a time. Both pairs of arrows work in both shapes. Enter takes the focused
 * option, which starts on the default.
 */
public final class ListSelect implements Question<String> {

    /** The most options an inline row holds. */
    static final int INLINE_MAX = 5;

    /** Visible rows of the vertical list. */
    static final int ROWS = 10;

    private final String label;
    private final @Nullable String help;
    private final List<Option> options;
    private final @Nullable String defaultId;
    private int focus;

    public ListSelect(String label, @Nullable String help, List<Option> options, @Nullable String defaultId) {
        if (options.isEmpty()) throw new IllegalArgumentException(label + ": no options");
        this.label = label;
        this.help = help;
        this.options = List.copyOf(options);
        this.defaultId = defaultId;
        this.focus = Math.max(0, Choices.indexOf(this.options, defaultId));
    }

    /** Whether the options render as one row at {@code ctx}'s width. */
    boolean inline(RenderContext ctx) {
        if (options.size() > INLINE_MAX) return false;
        int cols = Rows.INDENT.length();
        for (Option o : options) cols += Width.columns(o.display()) + 3;
        return cols < ctx.width();
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
        if (inline(ctx)) {
            StringBuilder row = new StringBuilder(Rows.INDENT);
            for (int i = 0; i < options.size(); i++) {
                if (i > 0) row.append(' ');
                row.append(Rows.segment(ctx, options.get(i).display(), i == focus));
            }
            rows.add(row.toString());
            String focusedHelp = options.get(focus).help();
            if (focusedHelp != null) rows.add(Rows.hint(ctx, focusedHelp));
            return rows;
        }
        int start = Rows.windowStart(focus, options.size(), ROWS);
        int end = Math.min(options.size(), start + ROWS);
        for (int i = start; i < end; i++) {
            Option o = options.get(i);
            String hint = o.help() == null ? "" : o.help();
            rows.add(Rows.INDENT + new RadioButton(o.display(), i == focus, i == focus, hint).renderInline(ctx));
        }
        if (end - start < options.size()) rows.add(Rows.hint(ctx, (focus + 1) + " of " + options.size()));
        return rows;
    }

    @Override
    public Reply<String> key(Key key) {
        return switch (key) {
            case Key.Left l -> move(-1);
            case Key.Up u -> move(-1);
            case Key.Right r -> move(1);
            case Key.Down d -> move(1);
            case Key.Tab t -> move(1);
            case Key.Enter e -> new Done<>(options.get(focus).id());
            case Key.Escape e -> new Cancelled<>();
            default -> new Pending<>();
        };
    }

    private Reply<String> move(int delta) {
        focus = Math.floorMod(focus + delta, options.size());
        return new Pending<>();
    }

    @Override
    public List<String> menu(RenderContext ctx) {
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            Option o = options.get(i);
            bodies.add(new RadioButton(o.display(), i == focus, false, o.help() == null ? "" : o.help())
                    .renderInline(ctx));
        }
        return Rows.numbered(bodies);
    }

    @Override
    public String cue() {
        int d = Choices.indexOf(options, defaultId);
        return d < 0 ? "Select" : "Select [" + (d + 1) + "]";
    }

    @Override
    public Reply<String> line(String line) {
        if (line.isEmpty()) {
            return defaultId == null ? new Retry<>(List.of(Choices.range(options))) : new Done<>(defaultId);
        }
        Option o = Choices.pick(options, line);
        return o == null ? new Retry<>(List.of(Choices.range(options))) : new Done<>(o.id());
    }

    @Override
    public String settled(String value) {
        int i = Choices.indexOf(options, value);
        return i < 0 ? value : options.get(i).display();
    }
}
