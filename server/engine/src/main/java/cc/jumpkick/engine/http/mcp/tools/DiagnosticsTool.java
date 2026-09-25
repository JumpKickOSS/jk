// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpDiagnostics;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.JkResultsAgent;
import java.util.Map;

/**
 * {@code jk_diagnostics} — the problems past the run reply's cap, or every problem in one file
 * with its source lines. {@code run} selects a history id (default: the newest run); {@code file}
 * or {@code module} narrows to a path.
 */
public final class DiagnosticsTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jk_diagnostics",
                "Failures past the verdict cap, or every failure in one file.",
                McpSchemas.object(Map.of(
                        "dir",
                        McpSchemas.string(),
                        "file",
                        McpSchemas.string(),
                        "severity",
                        McpSchemas.oneOf("error", "warning"),
                        "limit",
                        McpSchemas.integer())));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        String run = in.str("run");
        Map<String, Object> rec = run == null || run.isBlank()
                ? McpDiagnostics.findNewest(in.ctx().history(), in.dir())
                : McpDiagnostics.findRun(in.ctx().history(), run, in.dir());
        BuildRecord record = JkResultsAgent.recordOf(rec);
        if (record == null) {
            return in.ok(McpEnvelope.of("diagnostics", Map.of("count", 0)), "0 diagnostics\n");
        }
        String file = in.str("file");
        if (file == null || file.isBlank()) file = in.str("module");
        int limit = in.count("limit", 20, 1, 200);
        String text = JkResultsAgent.renderDetails(record, file, limit, true);
        return in.ok(McpEnvelope.of("diagnostics", Map.of("count", text.startsWith("0 ") ? 0 : 1)), text);
    }
}
