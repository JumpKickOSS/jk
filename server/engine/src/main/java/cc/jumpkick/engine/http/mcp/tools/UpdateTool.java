// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpJobRuns;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.engine.verbs.Updates;
import cc.jumpkick.host.Errors;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code update} — the {@link Updates preview} {@code jk update --preview} renders, or the {@code
 * update} job {@code jk update} runs. An apply takes every move on the pin's major, and past it with
 * {@code major}; it asks nobody.
 */
public final class UpdateTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "update",
                "Move declared exact pins in jk.toml to the newest stable on the same major (major=true"
                        + " crosses) and relock, as jk update. apply=false (default) previews the moves and"
                        + " the lock packages each adds; apply=true runs the update job and waits, as run"
                        + " does. Without major, newer majors are listed under available.",
                McpSchemas.withGoal(
                        Map.of(
                                "dir",
                                McpSchemas.string(McpSchemas.WORKSPACE_ROOT),
                                "deps",
                                McpSchemas.strings("Only these handles or group:artifact coordinates (default: all)"),
                                "major",
                                McpSchemas.bool("Allow a pin to cross its Maven major"),
                                "apply",
                                McpSchemas.bool()),
                        List.of()));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        if (in.flag("apply")) {
            Map<String, Object> args = new LinkedHashMap<>(in.args());
            args.remove("apply");
            return McpJobRuns.run(in.withArgs(args), "update");
        }
        Map<String, Object> data;
        try {
            data = Updates.structured(Updates.preview(in.requiredDir(), in.strings("deps"), in.flag("major")));
        } catch (Exception e) {
            data = Map.of("error", Errors.text(e));
        }
        Object moved = data.get("rewrites") instanceof List<?> l ? Integer.valueOf(l.size()) : data.get("error");
        return in.ok(McpEnvelope.of("update", data), "update preview " + moved);
    }
}
