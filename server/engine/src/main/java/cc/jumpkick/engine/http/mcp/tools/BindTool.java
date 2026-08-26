// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpHistoryViews;
import cc.jumpkick.engine.http.mcp.McpProjectCards;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.List;
import java.util.Map;

/** {@code jk_bind} — set the session's default workspace and answer with its project card. */
public final class BindTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jk_bind",
                "Set the default workspace for later tools (omit dir after this). Returns a project card.",
                McpSchemas.object(Map.of("dir", McpSchemas.string(McpSchemas.WORKSPACE_ROOT)), List.of("dir")));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        String dir = in.str("dir");
        if (dir == null || dir.isBlank()) throw new McpError(-32602, "jk_bind requires arguments.dir");
        String abs;
        try {
            abs = McpHistoryViews.dirKey(dir);
        } catch (RuntimeException e) {
            throw new McpError(-32602, "invalid dir: " + e.getMessage());
        }
        in.ctx().session().bind(abs);
        Map<String, Object> card = McpProjectCards.card(abs, in.ctx().history());
        Map<String, Object> env = McpEnvelope.of("project", card, false, null, "jk_history for recent runs");
        return in.ok(env, "bound " + card.getOrDefault("coord", abs));
    }
}
