// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.mcp.McpRevision;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns one step of a form into an MCP form-elicitation request, per kind:
 *
 * <ul>
 * <li>{@code text} is a string with its pattern; {@code bool} a boolean; {@code choice} a string enum.
 * <li>{@code multi} is an array of an enum where the revision has them, else one boolean per option,
 *     named {@code key.option}.
 * <li>{@code search} is an enum of the source's first {@value #MATCHES} matches plus {@link #MORE},
 *     which escalates when picked; no matches at all escalates at once.
 * <li>{@code path} escalates.
 * </ul>
 *
 * Only what is missing is asked: a field already answered, hidden, advanced, or optional (which a
 * field with a default is unless it says it is required) is left out. A required field with a
 * default is asked with the default filled in. A field whose {@code when} names a field asked in the same
 * request is deferred, so the caller flattens again once those answers are in.
 */
public final class McpFlattening {

    /** How many search matches go into the enum. */
    public static final int MATCHES = 8;

    /** The enum value that stands for "none of these": it has a space, so no option id can equal it. */
    public static final String MORE = "more …";

    private McpFlattening() {}

    /** The options a search field's source lists for {@code endpoint}, best first. */
    @FunctionalInterface
    public interface SearchMatches {
        List<Option> matches(Field field, String endpoint);
    }

    /** What to send for a step. */
    public sealed interface Elicitation permits Ask, Escalate, Complete {}

    /** What an elicitation's answer means. */
    public sealed interface Reply permits Answered, Escalate {}

    /** Nothing in the step is missing. */
    public record Complete() implements Elicitation {}

    /** The fields need the web wizard (URL mode): a path, a search with no matches, or "more" picked. */
    public record Escalate(List<String> keys, String reason) implements Elicitation, Reply {
        public Escalate {
            keys = List.copyOf(keys);
        }
    }

    /** Answers read back from an elicitation's content, keyed by field; check them with {@link Form#check}. */
    public record Answered(Map<String, Object> answers) implements Reply {
        public Answered {
            answers = Map.copyOf(answers);
        }
    }

    /** A form-mode request: {@code message} and {@code requestedSchema} go on the wire as they are. */
    public record Ask(String message, List<Field> fields, McpRevision revision, Map<String, Object> requestedSchema)
            implements Elicitation {
        public Ask {
            fields = List.copyOf(fields);
        }

        public String schemaJson() {
            return MiniJson.write(requestedSchema);
        }

        /** Maps the client's {@code content} back to field answers. */
        public Reply answers(Map<String, ?> content) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Field f : fields) {
                if (f.kind() == FieldKind.MULTI && !revision.arrayEnums()) {
                    List<String> picked = new ArrayList<>();
                    for (Option o : f.options()) {
                        if (Boolean.TRUE.equals(content.get(f.key() + "." + o.id()))) picked.add(o.id());
                    }
                    out.put(f.key(), picked);
                    continue;
                }
                Object value = content.get(f.key());
                if (f.kind() == FieldKind.SEARCH && MORE.equals(value)) {
                    return new Escalate(List.of(f.key()), f.key() + " needs a search the form cannot hold");
                }
                if (value != null) out.put(f.key(), value);
            }
            return new Answered(out);
        }
    }

    /**
     * The request for {@code step} given the answers already {@code known} (of any step). Earlier
     * fields that are not answered take their defaults when conditions are evaluated.
     */
    public static Elicitation flatten(
            Form form,
            Step step,
            Map<String, ?> known,
            McpRevision revision,
            DerivedDefaults derived,
            SearchMatches search) {
        if (!revision.formElicitation()) {
            throw new IllegalArgumentException("MCP " + revision.wire() + " has no form elicitation");
        }
        Map<String, Object> values = form.check(known, derived).values();
        List<Field> asked = new ArrayList<>();
        Set<String> pending = new HashSet<>();
        for (Field f : step.fields()) {
            if (known.containsKey(f.key())) continue;
            Condition when = f.when();
            if (when != null && when.keys().stream().anyMatch(pending::contains)) {
                pending.add(f.key());
                continue;
            }
            if (!Form.visible(f, values) || f.advanced() || !f.required()) continue;
            asked.add(f);
            pending.add(f.key());
        }
        if (asked.isEmpty()) return new Complete();
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Field f : asked) {
            switch (f.kind()) {
                case PATH -> {
                    return new Escalate(keys(asked), f.key() + " is a directory, which needs the wizard");
                }
                case SEARCH -> {
                    List<Option> top = search.matches(f, f.sourceFor(values));
                    if (top.isEmpty()) return new Escalate(keys(asked), f.key() + " has no matches to offer");
                    List<Entry> offered = new ArrayList<>(entries(top.subList(0, Math.min(MATCHES, top.size()))));
                    offered.add(new Entry(MORE, "More…", true));
                    properties.put(f.key(), oneOf(f, offered, revision));
                    required.add(f.key());
                }
                case MULTI -> {
                    List<?> ticked = Form.defaultOf(f, derived) instanceof List<?> l ? l : List.of();
                    if (revision.arrayEnums()) {
                        Map<String, Object> p = described(f, "array");
                        p.put("items", choices(entries(f.options()), revision, "anyOf"));
                        if (!ticked.isEmpty()) p.put("default", ticked);
                        properties.put(f.key(), p);
                        required.add(f.key());
                    } else {
                        for (Option o : f.options()) {
                            Map<String, Object> p = new LinkedHashMap<>();
                            p.put("type", "boolean");
                            p.put("title", o.display());
                            p.put("description", o.help() == null ? f.label() : f.label() + ": " + o.help());
                            p.put("default", ticked.contains(o.id()));
                            properties.put(f.key() + "." + o.id(), p);
                        }
                    }
                }
                case TEXT -> {
                    Map<String, Object> p = described(f, "string");
                    if (f.pattern() != null) p.put("pattern", f.pattern());
                    properties.put(f.key(), p);
                    required.add(f.key());
                }
                case BOOL -> {
                    properties.put(f.key(), described(f, "boolean"));
                    required.add(f.key());
                }
                case CHOICE -> {
                    properties.put(f.key(), oneOf(f, entries(f.options()), revision));
                    required.add(f.key());
                }
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return new Ask(step.title(), asked, revision, schema);
    }

    private static List<String> keys(List<Field> fields) {
        return fields.stream().map(Field::key).toList();
    }

    private static Map<String, Object> described(Field f, String type) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("title", f.label());
        if (f.help() != null) p.put("description", f.help());
        return p;
    }

    /** One enum value and how it reads; {@code labelled} when the title is more than the value. */
    private record Entry(String value, String title, boolean labelled) {}

    private static List<Entry> entries(List<Option> options) {
        return options.stream()
                .map(o -> new Entry(o.id(), o.display(), o.label() != null))
                .toList();
    }

    private static Map<String, Object> oneOf(Field f, List<Entry> entries, McpRevision revision) {
        Map<String, Object> p = described(f, "string");
        p.putAll(choices(entries, revision, "oneOf"));
        return p;
    }

    /**
     * An enum of {@code entries}. Titles ride along as {@code enumNames} on the older revision and as
     * {@code const} + {@code title} entries under {@code titled} ({@code oneOf} for one value,
     * {@code anyOf} for an array's items) on the newer one; entries without labels are a plain
     * {@code enum} on both.
     */
    private static Map<String, Object> choices(List<Entry> entries, McpRevision revision, String titled) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean labelled = entries.stream().anyMatch(Entry::labelled);
        if (labelled && revision.arrayEnums()) {
            if (titled.equals("oneOf")) out.put("type", "string");
            List<Object> consts = new ArrayList<>();
            for (Entry e : entries) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("const", e.value());
                entry.put("title", e.title());
                consts.add(entry);
            }
            out.put(titled, consts);
            return out;
        }
        out.put("type", "string");
        out.put("enum", entries.stream().map(Entry::value).toList());
        if (labelled) out.put("enumNames", entries.stream().map(Entry::title).toList());
        return out;
    }
}
