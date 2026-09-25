// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.engine.http.mcp.McpVitals;
import java.util.Map;

/** {@code status} — engine vitals, live jobs and the last run; same facts as the session resource. */
public final class StatusTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "status",
                "Engine vitals (pid, version, heap, active jobs). Same facts as GET /api/status and "
                        + "jk engine status --output json.",
                McpSchemas.object(Map.of()));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        Map<String, Object> status = McpVitals.statusPayload(in.ctx(), in.boundDir());
        return in.ok(status, McpVitals.statusSummary(status));
    }
}
