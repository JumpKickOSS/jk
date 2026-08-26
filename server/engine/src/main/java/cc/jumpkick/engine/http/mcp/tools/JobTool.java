// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpJobRuns;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/** {@code jk_job} — get / wait / cancel one job, defaulting to the newest live one. */
public final class JobTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jk_job",
                "get / wait / cancel a job. Omit jid to use the latest live job for the bound dir.",
                McpSchemas.object(Map.of(
                        "action",
                        McpSchemas.string("get | wait | cancel"),
                        "jid",
                        McpSchemas.integer(),
                        "timeout_s",
                        McpSchemas.integer())));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        return McpJobRuns.job(in);
    }
}
