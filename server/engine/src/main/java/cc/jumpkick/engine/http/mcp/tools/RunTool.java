// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpJobRuns;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/** {@code jk_run} — the general job verb every other job tool is an alias of. */
public final class RunTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jk_run",
                "Start a job (build|test|lock|update|format|native|image|assemble|compile|clean|publish|install|import; publish is always a dry-run — credentialed uploads are CLI-only). "
                        + "wait defaults true. dir optional after jk_bind. Aliases: jk_build/jk_test/jk_lock.",
                McpSchemas.object(Map.of(
                        "kind",
                        McpSchemas.string(
                                "build|test|lock|update|format|native|image|assemble|compile|clean|publish|install|import"),
                        "dir",
                        McpSchemas.string(McpSchemas.BOUND_ROOT),
                        "modules",
                        McpSchemas.strings("Module names/globs"),
                        "include_tags",
                        McpSchemas.strings(),
                        "exclude_tags",
                        McpSchemas.strings(),
                        "suites",
                        McpSchemas.strings(),
                        "skip_tests",
                        McpSchemas.bool(),
                        "wait",
                        McpSchemas.bool("Block until finish (default true)"),
                        "timeout_s",
                        McpSchemas.integer("Wait timeout seconds (default 600, max 3600)"))));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        return McpJobRuns.run(in, null);
    }
}
