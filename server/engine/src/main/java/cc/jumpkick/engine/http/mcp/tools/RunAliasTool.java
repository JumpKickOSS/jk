// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpJobRuns;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code publish} / {@code import} — a discoverable name for one {@code run} kind. The
 * body is {@code run}'s, pinned: a second job path would be a second definition of "finished".
 */
public final class RunAliasTool implements McpTool {

    private final Spec spec;
    private final String kind;

    public RunAliasTool(String name, String kind, String description, String dirDescription) {
        this(name, kind, description, dirDescription, Map.of());
    }

    /** As above, with {@code extra} schema properties (a boolean the kind's {@code run} reads). */
    public RunAliasTool(
            String name, String kind, String description, String dirDescription, Map<String, Object> extra) {
        this.kind = kind;
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("dir", McpSchemas.string(dirDescription));
        properties.put("wait", McpSchemas.bool("Block until finish (default true)"));
        properties.putAll(extra);
        this.spec = new Spec(name, description, McpSchemas.object(properties));
    }

    @Override
    public Spec spec() {
        return spec;
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        return McpJobRuns.run(in, kind);
    }
}
