// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.docs.JkManual;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/** {@code jk_manual} — the agent playbook, as markdown, from the one manual owner. */
public final class ManualTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jk_manual",
                "The jk playbook (markdown): read it before editing jk.toml.",
                McpSchemas.object(Map.of()),
                McpSchemas.READ_ONLY);
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        return in.ok(McpEnvelope.of("manual", Map.of("resource", "jk://manual")), JkManual.markdown());
    }
}
