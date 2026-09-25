// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.FeatureSelection;
import cc.jumpkick.runtime.RepoGroupBuilder;
import cc.jumpkick.runtime.base.GraphOps;
import cc.jumpkick.wire.protocol.WhyReport;
import java.util.Map;

/** {@code why} — the dependency path and the rule that picked the version, at most five lines. */
public final class WhyTool implements McpTool {

    static final String DESCRIPTION =
            "Why a version: the dependency path and the rule that picked it. coord=group:artifact.";

    @Override
    public Spec spec() {
        return new Spec(
                "why",
                DESCRIPTION,
                McpSchemas.object(Map.of("coord", McpSchemas.string(), "dir", McpSchemas.string())),
                McpSchemas.READ_ONLY);
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        String coord = in.str("coord");
        if (coord == null || coord.isBlank()) throw new McpError(-32602, "why requires arguments.coord");
        WhyReport report = GraphOps.why(
                PathUtil.resolveUserPath(in.requiredDir()),
                coord,
                FeatureSelection.DEFAULTS,
                RepoGroupBuilder::buildFor);
        return in.ok(McpEnvelope.of("why", report.toStructured()), WhyLines.of(report, coord));
    }
}
