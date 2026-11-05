// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.form;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.jsonl.MiniJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The form's wire shape, through the {@link MiniJson} tree codec:
 *
 * <pre>
 * {"id":"…","title":"…","rev":"…","steps":[{"id":"…","title":"…","fields":[
 *   {"key":"…","label":"…","help":"…","kind":"choice","options":["a",{"id":"b","label":"…","help":"…"}],
 *    "default":"a" | "from":"git-email","required":false,"pattern":"…","when":"…","advanced":true,
 *    "source":"…"}]}]}
 * </pre>
 *
 * An option with neither label nor help is written as its bare id. {@code required} is always
 * written; the other attributes only when set.
 */
final class FormJson {

    private FormJson() {}

    static String encode(Form form) {
        Map<String, Object> tree = tree(form);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", tree.get("id"));
        out.put("title", tree.get("title"));
        out.put("rev", rev(tree));
        out.put("steps", tree.get("steps"));
        return MiniJson.write(out);
    }

    static String rev(Form form) {
        return rev(tree(form));
    }

    private static String rev(Map<String, Object> tree) {
        return Hashing.sha256Hex(MiniJson.write(tree)).substring(0, 16);
    }

    private static Map<String, Object> tree(Form form) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", form.id());
        out.put("title", form.title());
        List<Object> steps = new ArrayList<>();
        for (Step s : form.steps()) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("id", s.id());
            step.put("title", s.title());
            step.put("fields", s.fields().stream().map(FormJson::field).toList());
            steps.add(step);
        }
        out.put("steps", steps);
        return out;
    }

    private static Map<String, Object> field(Field f) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", f.key());
        out.put("label", f.label());
        putIfSet(out, "help", f.help());
        out.put("kind", f.kind().id());
        if (!f.options().isEmpty())
            out.put("options", f.options().stream().map(FormJson::option).toList());
        switch (f.defaultValue()) {
            case null -> {}
            case FieldDefault.Literal literal -> out.put("default", literal.value());
            case FieldDefault.Derived derived -> out.put("from", derived.from());
        }
        out.put("required", f.required());
        putIfSet(out, "pattern", f.pattern());
        Condition when = f.when();
        putIfSet(out, "when", when == null ? null : when.source());
        if (f.advanced()) out.put("advanced", true);
        putIfSet(out, "source", f.source());
        return out;
    }

    private static Object option(Option o) {
        if (o.label() == null && o.help() == null) return o.id();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", o.id());
        putIfSet(out, "label", o.label());
        putIfSet(out, "help", o.help());
        return out;
    }

    private static void putIfSet(Map<String, Object> out, String name, @Nullable String value) {
        if (value != null) out.put(name, value);
    }

    static Form decode(String json) {
        Object root;
        try {
            root = MiniJson.parse(json);
        } catch (IllegalArgumentException e) {
            throw new FormException("form JSON does not parse: " + e.getMessage());
        }
        List<Step> steps = new ArrayList<>();
        List<?> rawSteps = MiniJson.list(root, "steps");
        for (int i = 0; i < rawSteps.size(); i++) {
            Object s = rawSteps.get(i);
            String at = "steps[" + i + "]";
            List<Field> fields = new ArrayList<>();
            List<?> rawFields = MiniJson.list(s, "fields");
            for (int j = 0; j < rawFields.size(); j++) {
                fields.add(field(rawFields.get(j), at + ".fields[" + j + "]"));
            }
            steps.add(new Step(string(s, "id", at), string(s, "title", at), fields));
        }
        Form form = new Form(string(root, "id", "form"), string(root, "title", "form"), steps);
        String rev = MiniJson.str(root, "rev");
        if (rev != null && !rev.equals(form.rev())) {
            throw new FormException("form rev " + rev + " does not match its content (" + form.rev() + ")");
        }
        return form;
    }

    private static Field field(@Nullable Object node, String at) {
        String key = string(node, "key", at);
        String where = at + " (" + key + ")";
        Field.Builder b = Field.of(key, string(node, "label", where), FieldKind.of(string(node, "kind", where)))
                .help(MiniJson.str(node, "help"))
                .pattern(MiniJson.str(node, "pattern"))
                .when(MiniJson.str(node, "when"))
                .source(MiniJson.str(node, "source"))
                .advanced(Boolean.TRUE.equals(MiniJson.get(node, "advanced")));
        for (Object o : MiniJson.list(node, "options")) {
            if (o instanceof String id) {
                b.option(id);
            } else {
                b.option(string(o, "id", where + " option"), MiniJson.str(o, "label"), MiniJson.str(o, "help"));
            }
        }
        Object literal = MiniJson.get(node, "default");
        String from = MiniJson.str(node, "from");
        if (literal != null && from != null) throw new FormException(where + ": has both default and from");
        if (literal != null) b.defaultValue(literal);
        if (from != null) b.defaultFrom(from);
        if (MiniJson.get(node, "required") instanceof Boolean required) b.required(required);
        return b.build();
    }

    private static String string(@Nullable Object node, String name, String at) {
        String value = MiniJson.str(node, name);
        if (value == null) throw new FormException(at + ": " + name + " is missing");
        return value;
    }
}
