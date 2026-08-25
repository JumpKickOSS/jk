// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON Schema fragments for {@link McpTool.Spec#inputSchema()}. The repeated {@code dir} wordings
 * are constants here rather than prose retyped per tool: an agent that learns one phrasing must
 * not meet a second for the same argument.
 */
public final class McpSchemas {

    /** Read-only tools agents should prefer for diagnosis (jk_results / jk_details / jk_manual). */
    public static final Map<String, Object> READ_ONLY =
            Map.of("readOnlyHint", true, "idempotentHint", true, "openWorldHint", false);

    /** A checkout that must contain a {@code jk.toml} — the tools that act on a whole workspace. */
    public static final String WORKSPACE_ROOT = "Project/workspace root (jk.toml): absolute, ~/…, or home-relative";

    /** The same root, named for a single project. */
    public static final String PROJECT_ROOT = "Project root (jk.toml): absolute, ~/…, or home-relative";

    /** A read that defaults to {@code jk_bind}'s dir. */
    public static final String BOUND_ROOT = "Project root (default: bound dir)";

    /** A history filter that defaults to {@code jk_bind}'s dir. */
    public static final String CHECKOUT_FILTER = "Checkout filter (default: bound dir)";

    private McpSchemas() {}

    public static Map<String, Object> object(Map<String, Object> properties) {
        return object(properties, List.of());
    }

    public static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required != null && !required.isEmpty()) schema.put("required", required);
        return schema;
    }

    /** The single-{@code dir} schema most read tools take. */
    public static Map<String, Object> dirOnly(String description) {
        return object(Map.of("dir", string(description)));
    }

    public static Map<String, Object> string() {
        return Map.of("type", "string");
    }

    public static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    public static Map<String, Object> integer() {
        return Map.of("type", "integer");
    }

    public static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    public static Map<String, Object> bool() {
        return Map.of("type", "boolean");
    }

    public static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    public static Map<String, Object> strings() {
        return Map.of("type", "array", "items", Map.of("type", "string"));
    }

    public static Map<String, Object> strings(String description) {
        return Map.of("type", "array", "items", Map.of("type", "string"), "description", description);
    }
}
