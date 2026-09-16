// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpDiagnostics;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code jk_diagnostics} — the compiler/test failures of one run, deduped and paged. The card
 * lists the loop's arguments; {@code run}, {@code module}, {@code unique} and {@code next} are
 * read too (a truncated page's hint names {@code next}) and the playbook's MCP page spells them
 * out.
 */
public final class DiagnosticsTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jk_diagnostics",
                "Structured compiler and test failures of the last failed run.",
                McpSchemas.object(Map.of(
                        "dir",
                        McpSchemas.string(),
                        "severity",
                        McpSchemas.oneOf("error", "warning"),
                        "limit",
                        McpSchemas.integer())));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        McpDiagnostics.Query query = new McpDiagnostics.Query(
                in.str("run"),
                in.dir(),
                in.str("module"),
                in.str("severity"),
                in.flagOr("unique", true),
                in.count("limit", 20, 1, 200),
                in.count("next", 0, 0, Integer.MAX_VALUE));
        McpDiagnostics.Page page = McpDiagnostics.page(in.ctx().history(), query);
        if (page == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("diagnostics", List.of());
            empty.put("count", 0);
            return in.ok(McpEnvelope.of("diagnostics", empty, false, null, "no matching failed run"), "0 diagnostics");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("run", page.run());
        fields.put("diagnostics", page.rows());
        fields.put("count", page.rows().size());
        fields.put("totalMatched", page.totalMatched());
        String hint =
                page.truncated() ? "jk_diagnostics next=" + page.next() : "jk_run kind=build wait=true to rebuild";
        return in.ok(
                McpEnvelope.of("diagnostics", fields, page.truncated(), page.next(), hint),
                page.rows().size() + " of " + page.totalMatched() + " diagnostics");
    }
}
