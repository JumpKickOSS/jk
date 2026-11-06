// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.giter8;

import cc.jumpkick.form.DerivedDefaults;
import cc.jumpkick.form.Field;
import cc.jumpkick.form.FieldDefault;
import cc.jumpkick.form.FieldKind;
import cc.jumpkick.form.FormCheck;
import cc.jumpkick.form.Option;
import cc.jumpkick.form.Step;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Turns a template's {@code [[ask]]} answers into Giter8 properties. StringTemplate conditionals
 * test truthiness only, so each {@code choice} also sets {@code <key>_<option>} to {@code yes} for
 * the chosen option and {@code no} for the rest, a {@code multi} does the same for each selected
 * option, and a {@code bool} is {@code yes} or {@code no}. A template writes
 * {@code $if(database_postgres.truthy)$} and carries no logic of its own.
 */
public final class TemplateAnswers {

    private TemplateAnswers() {}

    /**
     * {@code params} with every ask settled and bound (unchanged for a template with no
     * descriptor): a parameter answers the ask of its key, an
     * unanswered or hidden ask takes its default, and every other parameter passes through.
     *
     * @throws IllegalArgumentException naming each ask whose answer does not fit, or that is
     *     required and unanswered
     */
    public static Map<String, String> bind(JkTemplateToml.@Nullable Meta meta, Map<String, String> params) {
        Map<String, String> out = new LinkedHashMap<>(params);
        if (meta == null) return out;
        Optional<Step> step = meta.step();
        if (step.isEmpty()) return out;
        Map<String, Object> answers = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (Field f : step.get().fields()) {
            String raw = params.get(f.key());
            if (raw == null) continue;
            Object typed = typed(f, raw);
            if (typed == null) {
                problems.add(f.key() + " must be true or false; got '" + raw + "'" + hint(meta, f.key()));
            } else {
                answers.put(f.key(), typed);
            }
        }
        FormCheck check = meta.form().orElseThrow().check(answers, DerivedDefaults.NONE);
        for (FormCheck.Problem p : check.problems()) problems.add(p.message() + hint(meta, p.key()));
        if (!problems.isEmpty()) throw new IllegalArgumentException(String.join("; ", problems));
        for (Field f : step.get().fields()) {
            Object value = check.values().get(f.key());
            if (value == null) {
                out.remove(f.key());
                continue;
            }
            out.put(f.key(), text(value));
            switch (f.kind()) {
                case CHOICE -> {
                    for (Option o : f.options()) {
                        out.put(JkTemplateToml.bindingName(f.key(), o.id()), o.id().equals(value) ? "yes" : "no");
                    }
                }
                case MULTI -> {
                    List<?> picked = (List<?>) value;
                    for (Option o : f.options()) {
                        out.put(JkTemplateToml.bindingName(f.key(), o.id()), picked.contains(o.id()) ? "yes" : "no");
                    }
                }
                default -> {}
            }
        }
        return out;
    }

    /** The property text of a settled value: a bool is {@code yes} or {@code no}, a list joins on commas. */
    static String text(Object value) {
        if (value instanceof Boolean b) return b ? "yes" : "no";
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.joining(","));
        }
        return String.valueOf(value);
    }

    static @Nullable Object literalDefault(Field f) {
        return f.defaultValue() instanceof FieldDefault.Literal literal ? literal.value() : null;
    }

    /**
     * A command-line answer as the kind's type: a bool reads {@code true}/{@code yes}/{@code y} or
     * {@code false}/{@code no}/{@code n} ({@code null} for anything else), a multi splits on commas.
     */
    static @Nullable Object typed(Field f, String raw) {
        String s = raw.strip();
        return switch (f.kind()) {
            case BOOL ->
                switch (s.toLowerCase(Locale.ROOT)) {
                    case "true", "yes", "y" -> Boolean.TRUE;
                    case "false", "no", "n" -> Boolean.FALSE;
                    default -> null;
                };
            case MULTI -> {
                List<String> ids = new ArrayList<>();
                for (String part : s.split(",")) {
                    if (!part.isBlank()) ids.add(part.strip());
                }
                yield ids;
            }
            default -> raw;
        };
    }

    private static String hint(JkTemplateToml.Meta meta, String key) {
        for (Field f : meta.asks()) {
            if (!f.key().equals(key)) continue;
            if (f.kind() == FieldKind.CHOICE || f.kind() == FieldKind.MULTI) {
                String sep = f.kind() == FieldKind.CHOICE ? "|" : ",";
                return " (-p " + key + "="
                        + f.options().stream().map(Option::id).collect(Collectors.joining(sep)) + ")";
            }
            if (f.kind() == FieldKind.BOOL) return " (-p " + key + "=true|false)";
            return " (-p " + key + "=…)";
        }
        return "";
    }
}
