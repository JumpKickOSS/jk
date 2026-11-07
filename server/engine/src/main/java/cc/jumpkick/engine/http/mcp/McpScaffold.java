// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.engine.runtime.NewProjectAgents;
import cc.jumpkick.engine.runtime.NewProjectForm;
import cc.jumpkick.engine.runtime.NewProjectOps;
import cc.jumpkick.form.Field;
import cc.jumpkick.form.FieldLine;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.FormCheck;
import cc.jumpkick.form.Values;
import cc.jumpkick.giter8.Giter8TemplateIndex;
import cc.jumpkick.scaffold.NewProjectStep;
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

    /** The argument that answers the form's {@code dir}; MCP's own {@code dir} names the project a call is about. */
    static final String PARENT_DIR_ARG = "parentDir";

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

    /**
     * One template's form, one compact line per field: jk's step (each line keyed by the argument
     * that answers it), then the template's asks, which {@code params} answers. No template is the
     * default one.
     */
    public static Map<String, Object> form(@Nullable String template, @Nullable String lang, Path cwd)
            throws IOException {
        NewProjectOps.TemplateForm t = NewProjectOps.templateForm(orDefault(template), lang, cwd);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("template", t.id());
        m.put(
                "fields",
                NewProjectForm.of(t).fields().stream().map(McpScaffold::line).toList());
        return m;
    }

    /** A field's line, keyed by the argument that answers it: {@code dir} is {@code parentDir} here. */
    static String line(Field f) {
        if (!f.key().equals("dir")) return FieldLine.of(f);
        return FieldLine.of(new Field(
                PARENT_DIR_ARG,
                f.label(),
                f.help(),
                f.kind(),
                f.options(),
                f.defaultValue(),
                f.required(),
                f.pattern(),
                f.when(),
                f.advanced(),
                f.source()));
    }

    private static String orDefault(@Nullable String template) {
        return template == null || template.isBlank() ? NewProjectStep.DEFAULT_TEMPLATE : template.strip();
    }

    /** What a call does: scaffold a request, or report the project the human created in the wizard. */
    public sealed interface Plan {
        record Scaffold(NewProjectOps.Request request) implements Plan {}

        /** {@code project} holds the created project's {@code path} and {@code projectId}. */
        record Created(Map<String, Object> project) implements Plan {}
    }

    /**
     * The plan for a call. The template's form (the default template's when the call names none) is
     * settled first: the call's arguments and {@code params} answer it, defaults fill the rest, and a
     * required field still missing is asked through elicitation, by form or, with {@code wizard}, by
     * a link to the dashboard's wizard, or named in the error ({@link McpNewForm}). A relative
     * template path resolves against {@code cwd}, for the form and the scaffold alike. The project
     * is wired for the engine's agents setting and the calling client's agent ({@link McpClientAgent}).
     */
    public static Plan plan(McpCall in, String parentDir, Path cwd, boolean wizard) throws IOException {
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
        NewProjectOps.TemplateForm t = NewProjectOps.templateForm(orDefault(template), lang, cwd);
        Form form = NewProjectForm.of(t);
        Map<String, Object> given = new LinkedHashMap<>(params);
        given.put("template", t.id());
        given.put("dir", parentDir);
        putIfSet(given, "name", name);
        putIfSet(given, "group", group);
        putIfSet(given, "lang", lang);
        if (form.field("layout") != null) putIfSet(given, "layout", layout);
        FormCheck settled;
        switch (McpNewForm.settle(in, form, NewProjectForm.typed(form, given), wizard)) {
            case McpNewForm.Settled.Created created -> {
                return new Plan.Created(created.project());
            }
            case McpNewForm.Settled.Answers answers -> settled = answers.check();
        }
        Map<String, Object> values = settled.values();
        name = (String) values.get("name");
        if (values.get("group") instanceof String g) group = g;
        if (values.get("layout") instanceof String l) layout = l;
        Map<String, String> passthrough = new LinkedHashMap<>(params);
        for (Field f : form.fields()) passthrough.remove(f.key());
        Map<String, String> answers = new LinkedHashMap<>(NewProjectForm.templateParams(values));
        answers.putAll(passthrough);
        return new Plan.Scaffold(new NewProjectOps.Request(
                        name, parentDir, group, lang, layout, t.id(), true, answers, false, null, cwd.toString())
                .withAgents(McpClientAgent.choice(in.connection(), NewProjectAgents.current())));
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
        m.put("agents", NewProjectAgents.json(c.agents(), c.path()));
        return m;
    }
}
