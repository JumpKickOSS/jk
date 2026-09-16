// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One MCP tool. Not sealed — the set grows and tests need fakes. Not {@code ServiceLoader}: the
 * set is {@link McpTools#standard()}, an explicit list, so adding {@code jk_quux} is one class and
 * one line there.
 *
 * <p>{@link #spec()} is the only place the wire name is written. {@code tools/list} and
 * {@code tools/call} both read it, so the two cannot drift.
 */
public interface McpTool {

    /** Name, description and argument schema — read by {@code tools/list} and the dispatcher. */
    Spec spec();

    /** Run the tool. Throw {@link McpError} for a protocol-level failure. */
    Map<String, Object> call(McpCall in);

    /**
     * What {@code tools/list} publishes for one tool.
     *
     * @param name the wire name, {@code jk_*}, declared exactly once
     * @param description prose the model reads; say which CLI verb it mirrors
     * @param inputSchema JSON Schema for {@code arguments} (see {@link McpSchemas})
     * @param annotations MCP hints such as {@link McpSchemas#READ_ONLY}; empty when mutating
     */
    record Spec(
            String name,
            String description,
            Map<String, Object> inputSchema,
            @Nullable Map<String, Object> annotations) {

        public Spec(String name, String description, Map<String, Object> inputSchema) {
            this(name, description, inputSchema, null);
        }

        /** The description up to its first sentence end — what a catalog row shows. */
        public String oneLiner() {
            int end = description.indexOf(". ");
            return end < 0 ? description : description.substring(0, end + 1);
        }

        /** The {@code tools/list} row. */
        Map<String, Object> listed() {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", name);
            t.put("description", description);
            t.put("inputSchema", inputSchema);
            if (annotations != null && !annotations.isEmpty()) t.put("annotations", annotations);
            return t;
        }
    }
}
