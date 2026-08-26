// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpJobRuns;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.LinkedHashMap;
import java.util.Map;

/** {@code jk_cancel} — one jid, or every live job for a checkout. */
public final class CancelTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jk_cancel",
                "Cancel an in-flight job by jid, or every live job for a dir. Grace then force workers.",
                McpSchemas.object(Map.of(
                        "jid",
                        McpSchemas.integer("Job id from jk_build / jk_test / jk_lock / job-start"),
                        "dir",
                        McpSchemas.string("Cancel every live job for this checkout"))));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        Long jid = in.num("jid");
        if (jid != null) return McpJobRuns.cancel(in, jid.longValue(), "unknown or already finished jid");
        String dir = in.str("dir");
        if (dir == null || dir.isBlank()) throw new McpError(-32602, "jk_cancel requires arguments.jid (or dir)");
        int count = in.ctx().jobs().cancelDir(dir);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("dir", dir);
        fields.put("cancelled", count);
        return in.ok(
                McpEnvelope.of("cancel", fields),
                count > 0 ? "cancelled " + count + " job(s)" : "no running jobs for dir");
    }
}
