// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * MCP tool payloads. The loop tools answer {@link #text} alone: hosts that see a {@code
 * structuredContent} show the model that instead of {@code content}, which would hide the verdict.
 * The extended tools answer {@link #toolResult}: the envelope ({@code schema}/{@code type}/{@code
 * truncated}/{@code next} plus fields) with a one-line summary.
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

    /** A text-only tools/call result; {@code error} sets {@code isError}. */
    public static Map<String, Object> text(String text, boolean error) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", text);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(content));
        if (error) result.put("isError", true);
        return result;
    }

    /**
     * MCP tools/call result: short text + structured envelope. An envelope carrying an {@code
     * error} field sets {@code isError: true} — hosts surface that flag to the model, and without
     * it a failed install/action reads as success to generic clients.
     */
    public static Map<String, Object> toolResult(Map<String, Object> envelope, String summary) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "text");
        content.put("text", summary == null ? "" : summary);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(content));
        result.put("structuredContent", envelope);
        if (envelope != null && envelope.get("error") != null) result.put("isError", true);
        return result;
    }
}
