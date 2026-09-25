// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpAgentText;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpDiagnostics;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpHistoryViews;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/**
 * {@code jk_results} — the agent rendering of a finished run, the same text {@code jk_run} returns
 * when it waits. The card lists {@code dir}; {@code run} (a history id) is read too.
 */
public final class ResultsTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jk_results",
                "The last run's verdict.",
                McpSchemas.object(Map.of("dir", McpSchemas.string())),
                McpSchemas.READ_ONLY);
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        String run = in.str("run");
        String dir = in.dir();
        boolean newest = run == null || run.isBlank() || "last".equalsIgnoreCase(run) || "latest".equalsIgnoreCase(run);
        Map<String, Object> rec = newest
                ? McpDiagnostics.findNewest(in.ctx().history(), dir)
                : McpDiagnostics.findRun(in.ctx().history(), run, dir);
        if (rec == null) {
            return in.ok(
                    McpEnvelope.of("results", Map.of("error", "no matching run"), false, null, null),
                    "no matching run\n");
        }
        String text = McpAgentText.of(in.ctx(), rec);
        if (text == null) text = "no matching run\n";
        String id = McpHistoryViews.str(rec, "id");
        return in.ok(McpEnvelope.of("results", Map.of("run", id)), text);
    }
}
