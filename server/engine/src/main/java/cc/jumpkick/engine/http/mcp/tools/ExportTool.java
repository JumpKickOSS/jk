// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpReads;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.List;
import java.util.Map;

/** {@code export} — write the model out as Maven / Gradle / BOM files. */
public final class ExportTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "export",
                "Export the full model as maven | gradle | bom files (same generators as jk export). "
                        + "Returns written paths; read them yourself. IDE files: ide.",
                McpSchemas.object(
                        Map.of(
                                "format",
                                McpSchemas.string("maven | gradle | bom"),
                                "dir",
                                McpSchemas.string(McpSchemas.BOUND_ROOT)),
                        List.of("format")));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        return in.ok(McpEnvelope.of("export", McpReads.export(in.requiredDir(), in.str("format"))), "export");
    }
}
