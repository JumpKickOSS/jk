// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpManifest;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/** {@code jk_manifest} — set the whitelisted jk.toml keys. {@code java} is a language level. */
public final class ManifestTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jk_manifest",
                "Set whitelisted jk.toml keys. java=N is language level, not jdk=N.",
                McpSchemas.object(
                        Map.of("java", McpSchemas.integer(), "apply", McpSchemas.bool(), "dir", McpSchemas.string())));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        String dir = in.requiredDir();
        Integer java = in.intOrNull("java");
        if (java == null) throw new McpError(-32602, "jk_manifest requires java");
        Map<String, Object> data = McpManifest.setJava(dir, java.intValue(), in.flag("apply"));
        return in.ok(McpEnvelope.of("manifest", data, false, null, ManifestEdits.relockHint(data)), "java=" + java);
    }
}
