// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpManifest;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.host.PathUtil;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * {@code deps} — add, remove, or pin dependencies in {@code jk.toml}, then relock. {@code
 * preview=true} writes nothing and does not lock.
 */
public final class DepsTool implements McpTool {

    static final String DESCRIPTION =
            "Add, remove, or pin coords in jk.toml and relock. action=add, remove, or pin; preview=true does not write.";

    @Override
    public Spec spec() {
        return new Spec(
                "deps",
                DESCRIPTION,
                McpSchemas.object(Map.of(
                        "action",
                        McpSchemas.oneOf("add", "remove", "pin"),
                        "coords",
                        McpSchemas.strings(),
                        "scope",
                        McpSchemas.string(),
                        "dir",
                        McpSchemas.string(),
                        "preview",
                        McpSchemas.bool())));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        String action = in.action("add").toLowerCase(Locale.ROOT);
        if (!action.equals("add") && !action.equals("remove") && !action.equals("pin")) {
            throw new McpError(-32602, "deps action must be add, remove, or pin");
        }
        boolean preview = in.flag("preview");
        Map<String, Object> data =
                McpManifest.deps(in.requiredDir(), action, in.strings("coords"), in.str("scope"), !preview);
        if (data.get("error") != null) {
            return in.ok(McpEnvelope.of("deps", data), String.valueOf(data.get("error")) + "\n");
        }
        String changed = DepsLock.changedLine(data);
        if (preview || !Boolean.TRUE.equals(data.get("applied"))) {
            String text = preview ? changed + "\npreview\n" : changed + "\n";
            return in.ok(McpEnvelope.of("deps", data), text);
        }
        Path dir = PathUtil.resolveUserPath(in.requiredDir());
        String lock = DepsLock.relock(dir);
        data.put("lock", "lock ok".equals(lock) ? "ok" : "fail");
        if (!"ok".equals(data.get("lock"))) data.put("error", lock);
        return in.ok(McpEnvelope.of("deps", data), changed + "\n" + lock + "\n");
    }
}
