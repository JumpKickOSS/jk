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

/**
 * {@code bind} — set the connection's default project dir and answer with its project card.
 * The explicit form; an unbound connection's first call that carries {@code dir} binds by itself.
 * The bind is the connection's alone: two agents on one engine each keep their own, and a call
 * that rides no connection has nothing to bind.
 */
public final class BindTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "bind",
                "Set or switch the project dir later calls default to.",
                McpSchemas.object(Map.of("dir", McpSchemas.string()), List.of("dir")));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        String dir = in.str("dir");
        if (dir == null || dir.isBlank()) throw new McpError(-32602, "bind requires arguments.dir");
        String abs;
        try {
            abs = McpHistoryViews.dirKey(dir);
        } catch (RuntimeException e) {
            throw new McpError(-32602, "invalid dir: " + e.getMessage());
        }
        if (in.connection() == null) {
            throw new McpError(
                    -32602,
                    "bind needs a connection: send the Mcp-Session-Id that initialize returned, or pass dir on each call");
        }
        in.connection().bind(abs);
        Map<String, Object> card = McpProjectCards.card(abs, in.ctx().history());
        Map<String, Object> env = McpEnvelope.of("project", card, false, null, "history for recent runs");
        return in.ok(env, "bound " + card.getOrDefault("coord", abs));
    }
}
