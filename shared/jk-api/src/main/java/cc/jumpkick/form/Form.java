// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * An ordered list of steps, each an ordered list of fields, served by the engine and rendered by
 * every surface. Building one checks it whole: keys are unique across steps, and every {@code when}
 * and {@code source} names earlier fields only. {@link #rev} is a hash of the content, so two
 * surfaces holding the same rev hold the same questions.
 */
public record Form(String id, String title, List<Step> steps) {

    public Form {
        if (!Step.ID.matcher(id).matches())
            throw new FormException("form id '" + id + "' must match " + Step.ID.pattern());
        if (title.isBlank()) throw new FormException("form " + id + ": title is empty");
        Field.requireLine("form " + id + " title", title);
        steps = List.copyOf(steps);
        if (steps.isEmpty()) throw new FormException("form " + id + " has no steps");
        validate(steps);
    }

    public static Form of(String id, String title, Step... steps) {
        return new Form(id, title, List.of(steps));
    }

    /** The content hash: 16 hex digits of the SHA-256 of the canonical JSON, without the rev itself. */
    public String rev() {
        return FormJson.rev(this);
    }

    /** The wire shape, {@code rev} included. */
    public String toJson() {
        return FormJson.encode(this);
    }

    /** Reads {@link #toJson}'s shape; a {@code rev} that disagrees with the content is refused. */
    public static Form fromJson(String json) {
        return FormJson.decode(json);
    }

    /** Every field, in form order. */
    public List<Field> fields() {
        List<Field> out = new ArrayList<>();
        for (Step s : steps) out.addAll(s.fields());
        return out;
    }

    public @Nullable Field field(String key) {
        for (Step s : steps) {
            for (Field f : s.fields()) {
                if (f.key().equals(key)) return f;
            }
        }
        return null;
    }

    /** Whether {@code field} is shown, given the values settled before it. */
    public static boolean visible(Field field, Map<String, ?> values) {
        Condition when = field.when();
        return when == null || when.test(values);
    }

    /**
     * The field's default: the literal, or what {@code derived} makes of the source name. A derived
     * value that does not fit the field counts as none.
     */
    public static @Nullable Object defaultOf(Field field, DerivedDefaults derived) {
        return switch (field.defaultValue()) {
            case null -> null;
            case FieldDefault.Literal literal -> literal.value();
            case FieldDefault.Derived d -> {
                Object value = derived.resolve(d.from());
                yield value == null || field.problem(value) != null ? null : value;
            }
        };
    }

    /**
     * Settles {@code answers} against the form, field by field in order. A hidden field takes its
     * default whatever was answered; a shown one takes its answer, which must fit its kind, options
     * and pattern, else its default; a required field left with neither is a problem, as is a key
     * the form does not have. An empty string is no answer to a required text field.
     */
    public FormCheck check(Map<String, ?> answers, DerivedDefaults derived) {
        Map<String, Object> values = new LinkedHashMap<>();
        Set<String> hidden = new LinkedHashSet<>();
        List<FormCheck.Problem> problems = new ArrayList<>();
        for (String key : answers.keySet()) {
            if (field(key) == null)
                problems.add(new FormCheck.Problem(key, "'" + key + "' is not a field of this form"));
        }
        for (Field f : fields()) {
            Object fallback = defaultOf(f, derived);
            if (!visible(f, values)) {
                hidden.add(f.key());
                if (fallback != null) values.put(f.key(), fallback);
                continue;
            }
            Object answer = answers.get(f.key());
            if (answer instanceof String text && text.isEmpty() && f.required()) answer = null;
            if (answer != null) {
                String problem = f.problem(answer);
                if (problem != null) {
                    problems.add(new FormCheck.Problem(f.key(), problem));
                } else {
                    values.put(f.key(), inOptionOrder(f, answer));
                }
            } else if (fallback != null) {
                values.put(f.key(), fallback);
            } else if (f.required()) {
                problems.add(new FormCheck.Problem(f.key(), f.key() + " needs a value"));
            }
        }
        return new FormCheck(values, hidden, problems);
    }

    private static Object inOptionOrder(Field f, Object answer) {
        if (!(answer instanceof List<?> picked)) return answer;
        List<String> ordered = new ArrayList<>();
        for (Option o : f.options()) {
            if (picked.contains(o.id())) ordered.add(o.id());
        }
        return List.copyOf(ordered);
    }

    private static void validate(List<Step> steps) {
        Set<String> stepIds = new HashSet<>();
        Set<String> all = new LinkedHashSet<>();
        for (Step s : steps) {
            if (!stepIds.add(s.id())) throw new FormException("step '" + s.id() + "' appears twice");
            for (Field f : s.fields()) {
                if (!all.add(f.key())) throw new FormException("field '" + f.key() + "' appears twice");
            }
        }
        Map<String, Field> earlier = new LinkedHashMap<>();
        for (Step s : steps) {
            for (Field f : s.fields()) {
                Condition when = f.when();
                if (when != null) when.check(f.key(), earlier, all);
                for (String key : f.sourceKeys()) {
                    if (!earlier.containsKey(key)) {
                        throw new FormException("source of '" + f.key() + "' (" + f.source() + ") names '" + key
                                + "', which is not an earlier field");
                    }
                }
                earlier.put(f.key(), f);
            }
        }
    }
}
