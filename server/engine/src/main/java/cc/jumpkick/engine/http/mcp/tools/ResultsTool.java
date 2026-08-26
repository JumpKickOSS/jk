// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpDiagnostics;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpResults;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/** {@code jk_results} — the markdown run report, the same file the CLI writes to target/. */
public final class ResultsTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jk_results",
                "High-level markdown report for the last run (or a history id): compile, tests, "
                        + "install, publish, native, image. Same as CLI `jk results` and "
                        + "target/jk-results.md. Prefer this over tailing the build or --verbose. "
                        + "Resource: jk://runs/latest/results.",
                McpSchemas.object(Map.of(
                        "run",
                        McpSchemas.string("last (default) or history id"),
                        "dir",
                        McpSchemas.string(McpSchemas.CHECKOUT_FILTER))),
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
        Map<String, Object> fields = McpResults.read(rec, in.ctx().detailsFile());
        String md = fields.get("markdown") instanceof String s ? s : "";
        String summary = !md.isBlank() ? md : String.valueOf(fields.getOrDefault("error", "results"));
        return in.ok(McpEnvelope.of("results", fields, false, null, "details.jsonl for step-by-step"), summary);
    }
}
