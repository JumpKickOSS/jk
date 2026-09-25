// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpReads;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/**
 * {@code affected_tests} — WIP module cone + advisory ranked test classes. Writes
 * {@code target/jk-tests-affected.md}. Does not compile or run.
 */
public final class AffectedTestsTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "affected_tests",
                "WIP module cone + advisory ranked test classes. Writes target/jk-tests-affected.md "
                        + "(does not touch jk-results.md). Payload ≤20 test rows (truncated=true if more). "
                        + "Does not compile or run. jk test --affected is the same list (table, no run). "
                        + "run kind=test affected=true re-ranks after compile and runs. "
                        + "Refuse rather than guess when too broad.",
                McpSchemas.object(Map.of(
                        "dir",
                        McpSchemas.string(McpSchemas.BOUND_ROOT),
                        "include_tags",
                        McpSchemas.strings(),
                        "exclude_tags",
                        McpSchemas.strings(),
                        "suites",
                        McpSchemas.strings(),
                        "modules",
                        McpSchemas.strings())));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        Map<String, Object> data = McpReads.affectedTests(
                in.requiredDir(),
                in.strings("include_tags"),
                in.strings("exclude_tags"),
                in.strings("suites"),
                in.strings("modules"));
        Object err = data.get("error");
        String summary = err != null ? "affected-tests refuse: " + err : "affected-tests ranked=" + data.get("ranked");
        return in.ok(McpEnvelope.of("affected-tests", data), summary);
    }
}
