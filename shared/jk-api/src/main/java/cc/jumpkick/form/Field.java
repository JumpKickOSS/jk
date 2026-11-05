// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * One question. {@code key} is the answer's name. {@code options} belong to {@code choice} and
 * {@code multi}, {@code pattern} to {@code text}, and {@code source} to {@code search}: an engine
 * endpoint whose {@code {key}} placeholders name earlier answers. {@code required} defaults to
 * {@code true} exactly when there is no default. An {@code advanced} field is not prompted in the
 * terminal or over MCP; its default applies unless a flag sets it.
 */
public record Field(
        String key,
        String label,
        @Nullable String help,
        FieldKind kind,
        List<Option> options,
        @Nullable FieldDefault defaultValue,
        boolean required,
        @Nullable String pattern,
        @Nullable Condition when,
        boolean advanced,
        @Nullable String source) {

    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Set<String> RESERVED = Set.of("in", "true", "false");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]*)}");

    public Field {
        if (!KEY.matcher(key).matches() || RESERVED.contains(key)) {
            throw new FormException(
                    "field key '" + key + "' must match " + KEY.pattern() + " and not be in, true or false");
        }
        if (label.isBlank()) throw new FormException(key + ": label is empty");
        requireLine(key + " label", label);
        requireLine(key + " help", help);
        options = List.copyOf(options);
        boolean hasOptions = kind == FieldKind.CHOICE || kind == FieldKind.MULTI;
        if (hasOptions && options.isEmpty()) throw new FormException(key + ": a " + kind.id() + " field needs options");
        if (!hasOptions && !options.isEmpty())
            throw new FormException(key + ": a " + kind.id() + " field has no options");
        Set<String> ids = new HashSet<>();
        for (Option o : options) {
            if (!ids.add(o.id())) throw new FormException(key + ": option '" + o.id() + "' appears twice");
        }
        if (pattern != null) {
            if (kind != FieldKind.TEXT) throw new FormException(key + ": only a text field has a pattern");
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw new FormException(key + ": pattern " + pattern + " does not compile: " + e.getDescription());
            }
        }
        if (kind == FieldKind.SEARCH && source == null)
            throw new FormException(key + ": a search field needs a source");
        if (kind != FieldKind.SEARCH && source != null)
            throw new FormException(key + ": only a search field has a source");
        if (defaultValue instanceof FieldDefault.Literal literal) {
            String problem = problem(key, kind, options, pattern, literal.value());
            if (problem != null) throw new FormException(key + ": default does not fit: " + problem);
        }
    }

    /** Starts a field; {@link Builder#build} applies the {@code required} default. */
    public static Builder of(String key, String label, FieldKind kind) {
        return new Builder(key, label, kind);
    }

    /** The option with {@code id}, or {@code null}. */
    public @Nullable Option option(String id) {
        return find(options, id);
    }

    /** The keys {@code source} names, in the order written. */
    public Set<String> sourceKeys() {
        Set<String> out = new LinkedHashSet<>();
        if (source == null) return out;
        Matcher m = PLACEHOLDER.matcher(source);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** {@code source} with each placeholder replaced by its URL-encoded value; an absent value is empty. */
    public String sourceFor(Map<String, ?> values) {
        if (source == null) throw new FormException(key + ": not a search field");
        Matcher m = PLACEHOLDER.matcher(source);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object value = values.get(m.group(1));
            String text = value == null ? "" : URLEncoder.encode(Values.text(value), StandardCharsets.UTF_8);
            m.appendReplacement(out, Matcher.quoteReplacement(text));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Why {@code value} is not an answer to this field, or {@code null} when it is. Checks the type for
     * the kind, the options and the pattern; not whether a value is required.
     */
    public @Nullable String problem(Object value) {
        return problem(key, kind, options, pattern, value);
    }

    private static @Nullable String problem(
            String key, FieldKind kind, List<Option> options, @Nullable String pattern, Object value) {
        return switch (kind) {
            case BOOL -> value instanceof Boolean ? null : key + " must be true or false";
            case TEXT -> {
                if (!(value instanceof String text)) yield key + " must be a string";
                if (pattern != null && !Pattern.compile(pattern).matcher(text).matches()) {
                    yield key + " must match " + pattern + "; got '" + text + "'";
                }
                yield null;
            }
            case CHOICE ->
                value instanceof String id && find(options, id) != null ? null : notAnOption(key, options, value);
            case MULTI -> {
                if (!(value instanceof List<?> list)) yield key + " must be a list of " + ids(options);
                for (Object item : list) {
                    if (!(item instanceof String id) || find(options, id) == null)
                        yield notAnOption(key, options, item);
                }
                yield new HashSet<>(list).size() == list.size() ? null : key + " lists an option twice";
            }
            case SEARCH, PATH ->
                value instanceof String text && !text.isEmpty() ? null : key + " must be a non-empty string";
        };
    }

    private static @Nullable Option find(List<Option> options, String id) {
        for (Option o : options) {
            if (o.id().equals(id)) return o;
        }
        return null;
    }

    private static String notAnOption(String key, List<Option> options, @Nullable Object value) {
        return key + " must be one of " + ids(options) + "; got '" + value + "'";
    }

    private static String ids(List<Option> options) {
        return options.stream().map(Option::id).collect(Collectors.joining(", "));
    }

    static void requireLine(String what, @Nullable String text) {
        if (text != null && (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)) {
            throw new FormException(what + " must be one line");
        }
    }

    /** Assembles a {@link Field}; every attribute but key, label and kind is optional. */
    public static final class Builder {
        private final String key;
        private final String label;
        private final FieldKind kind;
        private @Nullable String help;
        private final List<Option> options = new ArrayList<>();
        private @Nullable FieldDefault defaultValue;
        private @Nullable Boolean required;
        private @Nullable String pattern;
        private @Nullable Condition when;
        private boolean advanced;
        private @Nullable String source;

        private Builder(String key, String label, FieldKind kind) {
            this.key = key;
            this.label = label;
            this.kind = kind;
        }

        public Builder help(@Nullable String text) {
            this.help = text;
            return this;
        }

        public Builder option(String id) {
            options.add(Option.of(id));
            return this;
        }

        public Builder option(String id, @Nullable String optionLabel, @Nullable String optionHelp) {
            options.add(new Option(id, optionLabel, optionHelp));
            return this;
        }

        public Builder options(List<Option> all) {
            options.addAll(all);
            return this;
        }

        /** A literal default: a {@code String}, a {@code Boolean} or a {@code List<String>}. */
        public Builder defaultValue(Object value) {
            this.defaultValue = new FieldDefault.Literal(value);
            return this;
        }

        public Builder defaultFrom(String from) {
            this.defaultValue = new FieldDefault.Derived(from);
            return this;
        }

        public Builder defaultTo(@Nullable FieldDefault value) {
            this.defaultValue = value;
            return this;
        }

        public Builder required(boolean value) {
            this.required = value;
            return this;
        }

        public Builder pattern(@Nullable String regex) {
            this.pattern = regex;
            return this;
        }

        public Builder when(@Nullable String condition) {
            this.when = condition == null ? null : Condition.parse(condition);
            return this;
        }

        public Builder advanced(boolean value) {
            this.advanced = value;
            return this;
        }

        public Builder source(@Nullable String endpoint) {
            this.source = endpoint;
            return this;
        }

        public Field build() {
            boolean need = required == null ? defaultValue == null : required;
            return new Field(key, label, help, kind, options, defaultValue, need, pattern, when, advanced, source);
        }
    }
}
