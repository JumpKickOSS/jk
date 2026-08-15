// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * MCP tool payload: {@code schema}/{@code type}/{@code truncated}/{@code next} plus fields, served
 * as {@code structuredContent}. {@code content[0].text} is a short summary for dumb clients.
 */
public final class McpEnvelope {

    public static final int SCHEMA = 1;

    private McpEnvelope() {}

    public static Map<String, Object> of(String type, Map<String, Object> fields) {
        return of(type, fields, false, null, null);
    }

    public static Map<String, Object> of(
            String type, Map<String, Object> fields, boolean truncated, @Nullable Object next, @Nullable String hint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("type", type);
        m.put("truncated", truncated);
        m.put("next", next);
        if (hint != null && !hint.isBlank()) m.put("hint", hint);
        if (fields != null) {
            for (Map.Entry<String, Object> e : fields.entrySet()) {
                if (!m.containsKey(e.getKey())) m.put(e.getKey(), e.getValue());
            }
        }
        return m;
    }

    /** MCP tools/call result: short text + structured envelope. */
    public static Map<String, Object> toolResult(Map<String, Object> envelope, String summary) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", summary == null ? "" : summary);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(content));
        result.put("structuredContent", envelope);
        return result;
    }
}
