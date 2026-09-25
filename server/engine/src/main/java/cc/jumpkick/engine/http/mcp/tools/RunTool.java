// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpAgentText;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpDiagnostics;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpHistoryViews;
import cc.jumpkick.engine.http.mcp.McpJobRuns;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/**
 * {@code run} — start a job and wait, or read an earlier verdict. {@code run} (a history id) with
 * no {@code kind} is the read. {@code kind} starts a job. {@code only} limits modules.
 */
public final class RunTool implements McpTool {

    static final String DESCRIPTION =
            "Run and wait; the reply is the verdict. kind=build|test|lock|…; only=modules; suites=[integration]; run=<id> rereads a run.";

    @Override
    public Spec spec() {
        return new Spec(
                "run",
                DESCRIPTION,
                McpSchemas.object(Map.of(
                        "kind",
                        McpSchemas.string(),
                        "only",
                        McpSchemas.string(),
                        "dir",
                        McpSchemas.string(),
                        "run",
                        McpSchemas.string(),
                        "suites",
                        McpSchemas.strings())));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        String kind = in.str("kind");
        String run = in.str("run");
        if ((kind == null || kind.isBlank()) && run != null && !run.isBlank()) return earlier(in, run);
        return McpJobRuns.run(in, null);
    }

    /** The agent rendering of a finished run. {@code latest} is the newest. */
    private static Map<String, Object> earlier(McpCall in, String run) {
        String dir = in.dir();
        boolean newest = "last".equalsIgnoreCase(run) || "latest".equalsIgnoreCase(run);
        Map<String, Object> rec = newest
                ? McpDiagnostics.findNewest(in.ctx().history(), dir)
                : McpDiagnostics.findRun(in.ctx().history(), run, dir);
        if (rec == null) {
            return in.ok(McpEnvelope.of("run", Map.of("error", "no matching run")), "no matching run\n");
        }
        String text = McpAgentText.of(in.ctx(), rec);
        if (text == null) text = "no matching run\n";
        String id = McpHistoryViews.str(rec, "id");
        return in.ok(McpEnvelope.of("run", Map.of("run", id)), text);
    }
}
