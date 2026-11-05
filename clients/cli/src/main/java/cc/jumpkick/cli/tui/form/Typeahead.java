// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui.form;

import cc.jumpkick.cli.tui.RadioButton;
import cc.jumpkick.cli.tui.RenderContext;
import cc.jumpkick.form.Option;
import cc.jumpkick.terminal.Key;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One option of a large, dynamic list. Each edit of the query asks {@code search} for matches,
 * best first; the first {@value #SHOWN} are listed, ↑ / ↓ move among them and Enter takes the
 * focused one. Cooked, a line is a query: one match (or an exact id) answers, several are listed
 * by number for the next line to pick.
 */
public final class Typeahead implements Question<String> {

    /** Matches listed at once. */
    static final int SHOWN = 8;

    private final String label;
    private final @Nullable String help;
    private final Function<String, List<Option>> search;
    private final @Nullable String defaultId;
    private final StringBuilder query = new StringBuilder();
    private List<Option> matches;
    private int focus;
    private @Nullable String error;

    public Typeahead(
            String label, @Nullable String help, Function<String, List<Option>> search, @Nullable String defaultId) {
        this.label = label;
        this.help = help;
        this.search = search;
        this.defaultId = defaultId;
        this.matches = top("");
        this.focus = Math.max(0, Choices.indexOf(matches, defaultId));
    }

    private List<Option> top(String q) {
        List<Option> all = search.apply(q);
        return List.copyOf(all.subList(0, Math.min(SHOWN, all.size())));
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
        rows.add(Rows.input(ctx, query.toString(), "type to search"));
        if (matches.isEmpty()) {
            rows.add(Rows.hint(ctx, "no match"));
        }
        for (int i = 0; i < matches.size(); i++) {
            Option o = matches.get(i);
            String hint = o.help() == null ? "" : o.help();
            rows.add(Rows.INDENT + new RadioButton(o.display(), i == focus, i == focus, hint).renderInline(ctx));
        }
        if (error != null) rows.add(Rows.error(ctx, error));
        return rows;
    }

    @Override
    public Reply<String> key(Key key) {
        switch (key) {
            case Key.Up u -> focus = matches.isEmpty() ? 0 : Math.floorMod(focus - 1, matches.size());
            case Key.Down d -> focus = matches.isEmpty() ? 0 : Math.floorMod(focus + 1, matches.size());
            case Key.Enter e -> {
                if (!matches.isEmpty()) return new Done<>(matches.get(focus).id());
                error = "Nothing matches '" + query + "'.";
                return new Pending<>();
            }
            case Key.Escape e -> {
                return new Cancelled<>();
            }
            case Key.Backspace b -> {
                if (!query.isEmpty()) edit(query.substring(0, query.length() - 1));
            }
            case Key.Space s -> edit(query + " ");
            case Key.Char c -> edit(query.toString() + c.c());
            default -> {}
        }
        return new Pending<>();
    }

    private void edit(String next) {
        query.setLength(0);
        query.append(next);
        matches = top(next);
        focus = 0;
        error = null;
    }

    @Override
    public List<String> menu(RenderContext ctx) {
        return List.of();
    }

    @Override
    public String cue() {
        return defaultId == null ? "(type to search)" : "[" + defaultId + "]";
    }

    @Override
    public Reply<String> line(String line) {
        if (line.isEmpty()) {
            return defaultId == null ? new Retry<>(List.of("Type part of a name to search.")) : new Done<>(defaultId);
        }
        if (line.chars().allMatch(Character::isDigit) && !matches.isEmpty()) {
            Option o = Choices.pick(matches, line);
            if (o != null) return new Done<>(o.id());
        }
        List<Option> found = top(line);
        for (Option o : found) {
            if (o.id().equals(line)) return new Done<>(o.id());
        }
        if (found.size() == 1) return new Done<>(found.getFirst().id());
        matches = found;
        if (found.isEmpty()) return new Retry<>(List.of("Nothing matches '" + line + "'."));
        List<String> lines = new ArrayList<>(
                Rows.numbered(found.stream().map(Option::display).toList()));
        lines.add("Pick 1-" + found.size() + ", or search again.");
        return new Retry<>(lines);
    }

    @Override
    public String settled(String value) {
        return value;
    }
}
