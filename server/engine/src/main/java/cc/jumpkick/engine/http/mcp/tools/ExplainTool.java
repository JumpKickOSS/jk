// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.engine.jobs.JobRequests;
import cc.jumpkick.engine.verbs.Forecasts;
import cc.jumpkick.host.Errors;
import cc.jumpkick.wire.protocol.ExplainRequest;
import java.util.Map;
import java.util.Set;

/** {@code explain} — the {@link Forecasts forecast} of the build a {@code build} call of the same selection starts. */
public final class ExplainTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "explain",
                "Forecast what build would start for this selection: dirty modules, cache hits, ETA (jk explain).",
                McpSchemas.object(JobRequests.schemaProperties(Forecasts.FIELDS)));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        ExplainRequest req;
        try {
            req = Forecasts.request(in.args(), in.requiredDir(), Set.of(McpSchemas.GOAL));
        } catch (IllegalArgumentException e) {
            throw new McpError(-32602, e.getMessage());
        }
        Map<String, Object> data;
        try {
            data = Forecasts.structured(Forecasts.forecast(req));
        } catch (Exception e) {
            data = Map.of("error", Errors.text(e));
        }
        Object dirty = data.getOrDefault("dirtyCount", data.containsKey("errors") ? "error" : data.get("error"));
        return in.ok(McpEnvelope.of("explain", data), "explain dirty=" + dirty);
    }
}
