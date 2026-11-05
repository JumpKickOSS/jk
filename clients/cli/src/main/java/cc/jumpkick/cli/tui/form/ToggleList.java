// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.form;

import cc.jumpkick.cli.tui.Checkbox;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.form.Option;
import cc.jumpkick.terminal.Key;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Any subset of a list: ↑ / ↓ move, Space toggles the focused option, {@code a} toggles them all,
 * Enter takes the ticked ones in option order. Starts with the defaults ticked.
 */
public final class ToggleList implements Question<List<String>> {

    private final String label;
    private final @Nullable String help;
    private final List<Option> options;
    private final List<String> defaults;
    private final Set<String> ticked = new LinkedHashSet<>();
    private int focus;

    public ToggleList(String label, @Nullable String help, List<Option> options, List<String> defaults) {
        if (options.isEmpty()) throw new IllegalArgumentException(label + ": no options");
        this.label = label;
        this.help = help;
        this.options = List.copyOf(options);
        this.defaults = List.copyOf(defaults);
        ticked.addAll(defaults);
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
        int start = Rows.windowStart(focus, options.size(), ListSelect.ROWS);
        int end = Math.min(options.size(), start + ListSelect.ROWS);
        for (int i = start; i < end; i++) {
            Option o = options.get(i);
            String hint = o.help() == null ? "" : o.help();
            for (String row : new Checkbox(o.display(), ticked.contains(o.id()), i == focus, hint).render(ctx)) {
                rows.add(Rows.INDENT + row);
            }
        }
        rows.add(Rows.hint(ctx, ticked.size() + " of " + options.size() + " selected · space toggles · a all"));
        return rows;
    }

    @Override
    public Reply<List<String>> key(Key key) {
        switch (key) {
            case Key.Up u -> focus = Math.floorMod(focus - 1, options.size());
            case Key.Down d -> focus = Math.floorMod(focus + 1, options.size());
            case Key.Space s -> toggle(options.get(focus).id());
            case Key.Char c
            when Character.toLowerCase(c.c()) == 'a' -> {
                if (ticked.size() == options.size()) {
                    ticked.clear();
                } else {
                    options.forEach(o -> ticked.add(o.id()));
                }
            }
            case Key.Enter e -> {
                return new Done<>(inOrder(ticked));
            }
            case Key.Escape e -> {
                return new Cancelled<>();
            }
            default -> {}
        }
        return new Pending<>();
    }

    private void toggle(String id) {
        if (!ticked.remove(id)) ticked.add(id);
    }

    private List<String> inOrder(Set<String> ids) {
        return options.stream().map(Option::id).filter(ids::contains).toList();
    }

    @Override
    public List<String> menu(RenderContext ctx) {
        List<String> bodies = new ArrayList<>();
        for (Option o : options) {
            String hint = o.help() == null ? "" : o.help();
            bodies.add(new Checkbox(o.display(), defaults.contains(o.id()), false, hint)
                    .render(ctx)
                    .getFirst());
        }
        return Rows.numbered(bodies);
    }

    @Override
    public String cue() {
        String positions = Choices.positions(options, defaults);
        return "Select (e.g. 1 3, all, none)" + (positions.isEmpty() ? "" : " [" + positions + "]");
    }

    @Override
    public Reply<List<String>> line(String line) {
        if (line.isEmpty()) return new Done<>(inOrder(Set.copyOf(defaults)));
        switch (line.toLowerCase(Locale.ROOT)) {
            case "all" -> {
                return new Done<>(options.stream().map(Option::id).toList());
            }
            case "none" -> {
                return new Done<>(List.of());
            }
            default -> {}
        }
        Set<String> picked = new LinkedHashSet<>();
        for (String token : line.split("[\\s,]+")) {
            Option o = Choices.pick(options, token);
            if (o == null) return new Retry<>(List.of(Choices.range(options)));
            picked.add(o.id());
        }
        return new Done<>(inOrder(picked));
    }

    @Override
    public String settled(List<String> value) {
        if (value.isEmpty()) return "(none)";
        List<String> labels = new ArrayList<>();
        for (String id : value) {
            int i = Choices.indexOf(options, id);
            labels.add(i < 0 ? id : options.get(i).display());
        }
        return String.join(", ", labels);
    }
}
