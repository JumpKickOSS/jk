// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.engine.runtime.NewProjectOps;
import cc.jumpkick.scaffold.Giter8TemplateIndex;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP {@code jk_new}: the same {@link NewProjectOps} scaffolder as {@code jk new} and
 * {@code POST /api/projects} — never a second layout. Encoding only lives here.
 */
public final class McpScaffold {

    private McpScaffold() {}

    /** Catalog + local template short names, same index the picker and resolver use. */
    public static Map<String, Object> templates() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var e : Giter8TemplateIndex.build(Giter8TemplateIndex.searchRoots())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.id());
            row.put("description", e.description());
            row.put("languages", e.languages());
            row.put("layout", e.layout());
            rows.add(row);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("templates", rows);
        m.put("builtinLayouts", List.of("traditional", "simple"));
        m.put("builtinLangs", List.of("java", "kotlin", "groovy"));
        return m;
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
