// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpMachine;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/** {@code config} — read machine config, set one key, or apply the CI preset. */
public final class ConfigTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "config",
                "get / set machine config, or apply_preset=ci.",
                McpSchemas.object(Map.of(
                        "action",
                        McpSchemas.string("get | set | apply_preset"),
                        "key",
                        McpSchemas.string("nerd-font | engine.max-heap-mb"),
                        "value",
                        McpSchemas.string(),
                        "preset",
                        McpSchemas.string("ci"))));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        // Only the explicit action mutates — `action=get preset=ci` is a read, never a write.
        String action = in.action("get");
        if ("apply_preset".equals(action)) {
            return in.ok(McpEnvelope.of("config", McpMachine.applyCiPreset()), "ci preset");
        }
        if ("set".equals(action)) {
            String key = in.str("key");
            if (key == null) throw new McpError(-32602, "config set requires key");
            return in.ok(McpEnvelope.of("config", McpMachine.configSet(key, in.str("value"))), "set " + key);
        }
        return in.ok(McpEnvelope.of("config", McpMachine.configGet()), "config");
    }
}
