// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpJobRuns;
import cc.jumpkick.engine.http.mcp.McpMachine;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/**
 * {@code install} — install the project into the local Maven repo, or list the installed jkx
 * tools. Installing a tool stays CLI-side: the trust gate is a human decision.
 */
public final class InstallTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "install",
                "Install the project app into the local Maven repo (jk install), or "
                        + "action=list for installed jkx tools. Tool installs stay CLI-side (trust gates).",
                McpSchemas.object(Map.of(
                        "action",
                        McpSchemas.string("install (default) | list"),
                        "dir",
                        McpSchemas.string(McpSchemas.BOUND_ROOT),
                        "wait",
                        McpSchemas.bool("Block until finish (default true)"))));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        if ("list".equalsIgnoreCase(in.str("action"))) {
            return in.ok(McpEnvelope.of("tools", McpMachine.tools()), "installed tools");
        }
        return McpJobRuns.run(in, "install");
    }
}
