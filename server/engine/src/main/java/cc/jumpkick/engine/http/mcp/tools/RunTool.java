// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpJobRuns;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;

/**
 * {@code jk_run} — the general job verb every other job tool is an alias of. The card lists the
 * loop's arguments; {@code modules}, {@code suites}, {@code include_tags}, {@code exclude_tags},
 * {@code skip_tests} and {@code deadline_s} are read too and the playbook's MCP page spells them
 * out.
 */
public final class RunTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jk_run",
                "Run a jk job (kind, default build) and wait; the reply is that run's verdict.",
                McpSchemas.object(Map.of(
                        "kind",
                        McpSchemas.oneOf(
                                "build",
                                "test",
                                "guard",
                                "format",
                                "lock",
                                "update",
                                "compile",
                                "clean",
                                "assemble",
                                "native",
                                "image",
                                "publish",
                                "install",
                                "import"),
                        "dir",
                        McpSchemas.string(),
                        "wait",
                        McpSchemas.bool(),
                        "timeout_s",
                        McpSchemas.integer())));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        return McpJobRuns.run(in, null);
    }
}
