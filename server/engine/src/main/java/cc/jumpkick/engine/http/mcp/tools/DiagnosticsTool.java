// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpAgentText;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpDiagnostics;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.JkResultsAgent;
import java.util.Map;

/**
 * {@code diagnostics} — every problem of a run with its source lines (the headline's line past the
 * cap), or the problems in one file. {@code run} selects a history id (default: the newest run).
 */
public final class DiagnosticsTool implements McpTool {

    static final String DESCRIPTION = "Every problem with source lines; file= for one file.";

    @Override
    public Spec spec() {
        return new Spec(
                "diagnostics",
                DESCRIPTION,
                McpSchemas.object(
                        Map.of("file", McpSchemas.string(), "limit", McpSchemas.integer(), "dir", McpSchemas.string())),
                McpSchemas.READ_ONLY);
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        String run = in.str("run");
        Map<String, Object> rec = run == null || run.isBlank()
                ? McpDiagnostics.findNewest(in.ctx().history(), in.dir())
                : McpDiagnostics.findRun(in.ctx().history(), run, in.dir());
        String file = in.str("file");
        if (file == null || file.isBlank()) file = in.str("module");
        if (file == null || file.isBlank()) {
            String all = McpAgentText.all(in.ctx(), rec);
            return in.text(all == null ? "0 diagnostics\n" : all);
        }
        BuildRecord record = JkResultsAgent.recordOf(rec);
        if (record == null) return in.text("0 diagnostics\n");
        int limit = in.count("limit", 20, 1, JkResultsAgent.MAX_ALL);
        return in.text(JkResultsAgent.renderDetails(record, file, limit, true));
    }
}
