// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.engine.http.mcp.McpUpdate;
import java.util.List;
import java.util.Map;

/** {@code update} — preview or apply the pin rewrite and relock {@code jk update} performs. */
public final class UpdateTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "update",
                "Move declared exact pins in jk.toml to the newest stable on the same major (major=true"
                        + " crosses) and relock — same as jk update. apply=false (default) only previews"
                        + " the rewrites; apply=true writes jk.toml and jk-lock.toml and lists every lock"
                        + " package the relock added, removed or moved under lock.changes.",
                McpSchemas.object(Map.of(
                        "dir",
                        McpSchemas.string(McpSchemas.WORKSPACE_ROOT),
                        "deps",
                        McpSchemas.strings("Only these handles or group:artifact coordinates (default: all)"),
                        "major",
                        McpSchemas.bool("Allow a pin to cross its Maven major"),
                        "apply",
                        McpSchemas.bool())));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        boolean apply = in.flag("apply");
        Map<String, Object> data = McpUpdate.run(in.requiredDir(), in.strings("deps"), in.flag("major"), apply);
        Object moved = data.get("rewrites") instanceof List<?> l ? Integer.valueOf(l.size()) : data.get("error");
        String relocked = data.get("lock") instanceof Map<?, ?> lock && lock.get("updated") != null
                ? ", " + lock.get("updated") + " lock packages changed"
                : "";
        return in.ok(
                McpEnvelope.of("update", data), (apply ? "update applied " : "update preview ") + moved + relocked);
    }
}
