// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpHistoryViews;
import cc.jumpkick.engine.http.mcp.McpProjectCards;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/** {@code jk_project} — the project card for a checkout, keyed exactly as {@code jk_bind} keys it. */
public final class ProjectTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jk_project",
                "Project card (coord, java, members, last run). dir optional after jk_bind.",
                McpSchemas.dirOnly(McpSchemas.PROJECT_ROOT));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        String dir = in.requiredDir();
        String abs;
        try {
            abs = McpHistoryViews.dirKey(dir);
        } catch (RuntimeException e) {
            abs = dir;
        }
        Map<String, Object> card = McpProjectCards.card(abs, in.ctx().history());
        // Keep legacy lookup keys (coord/description) if parse failed.
        if (!card.containsKey("coord")) {
            card.putAll(in.ctx().projectLookup().apply(dir));
        }
        return in.ok(McpEnvelope.of("project", card), String.valueOf(card.getOrDefault("coord", abs)));
    }
}
