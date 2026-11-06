// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.engine.runtime.NewProjectForm;
import cc.jumpkick.engine.runtime.NewProjectOps;
import cc.jumpkick.form.Field;
import cc.jumpkick.form.FieldLine;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.FormCheck;
import cc.jumpkick.form.Values;
import cc.jumpkick.giter8.Giter8TemplateIndex;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * MCP {@code new}: the same {@link NewProjectOps} scaffolder as {@code jk new} and
 * {@code POST /api/projects} — never a second layout. Encoding and the form's settling live here.
 */
public final class McpScaffold {

    private McpScaffold() {}

    /**
     * Catalog + local + plugin templates, same index the picker and resolver use: one lean row each,
     * since a listing is paid for whole. A template's questions are {@link #form}'s.
     */
    public static Map<String, Object> templates() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var e : Giter8TemplateIndex.picker(Giter8TemplateIndex.searchRoots())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.id());
            row.put("language", e.language());
            row.put("framework", e.framework());
            row.put("description", e.description());
            rows.add(row);
        }
        return Map.of("templates", rows);
    }

    /** One template's fields, one compact line each: the layout when it is a choice, then its asks. */
    public static Map<String, Object> form(String template, @Nullable String lang, Path cwd) throws IOException {
        NewProjectOps.TemplateForm t = NewProjectOps.templateForm(template, lang, cwd);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("template", t.id());
        m.put(
                "fields",
                NewProjectForm.templateFields(NewProjectForm.of(t)).stream()
                        .map(FieldLine::of)
                        .toList());
        return m;
    }

    /**
     * The scaffold request for a call. With a template, its form is settled first: the call's
     * arguments and {@code params} answer it, defaults fill the rest, and a required field still
     * missing is asked through elicitation or named in the error ({@link McpNewForm}).
     */
    public static NewProjectOps.Request request(McpCall in, String parentDir) throws IOException {
        String name = in.str("name");
        String group = in.str("group");
        String lang = in.str("lang");
        String layout = in.str("layout");
        String template = in.str("template");
        Map<String, String> params = new LinkedHashMap<>();
        if (in.args().get("params") instanceof Map<?, ?> raw) {
            for (var e : raw.entrySet()) {
                if (e.getValue() != null) params.put(String.valueOf(e.getKey()), Values.text(e.getValue()));
            }
        }
        if (template != null && !template.isBlank()) {
            NewProjectOps.TemplateForm t = NewProjectOps.templateForm(template.strip(), lang, Path.of(parentDir));
            Form form = NewProjectForm.of(t);
            Map<String, Object> given = new LinkedHashMap<>(params);
            given.put("template", t.id());
            putIfSet(given, "name", name);
            putIfSet(given, "group", group);
            putIfSet(given, "lang", lang);
            if (form.field("layout") != null) putIfSet(given, "layout", layout);
            FormCheck settled = McpNewForm.settle(in, form, NewProjectForm.typed(form, given));
            Map<String, Object> values = settled.values();
            name = (String) values.get("name");
            if (values.get("group") instanceof String g) group = g;
            if (values.get("layout") instanceof String l) layout = l;
            template = t.id();
            Map<String, String> passthrough = new LinkedHashMap<>(params);
            for (Field f : form.fields()) passthrough.remove(f.key());
            params = new LinkedHashMap<>(NewProjectForm.templateParams(values));
            params.putAll(passthrough);
        }
        return new NewProjectOps.Request(
                name,
                parentDir,
                group,
                lang,
                layout,
                template,
                in.flagOr("executable", true),
                null,
                0,
                false,
                false,
                false,
                null,
                List.of(),
                true,
                true,
                params,
                false,
                null);
    }

    private static void putIfSet(Map<String, Object> out, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) out.put(key, value);
    }

    /** Preview: exact file set (scaffolded into scratch, never the target). */
    public static Map<String, Object> preview(NewProjectOps.Request req) throws IOException {
        NewProjectOps.Preview p = NewProjectOps.preview(req);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", p.path());
        if (p.template() != null) m.put("template", p.template());
        m.put("files", p.files());
        return m;
    }

    /** Create for real; identity materialized so id-routed surfaces work immediately. */
    public static Map<String, Object> create(NewProjectOps.Request req) throws IOException {
        NewProjectOps.Created c = NewProjectOps.createWithIdentity(req);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", c.path().toString());
        if (c.projectId() != null) m.put("projectId", c.projectId());
        return m;
    }
}
