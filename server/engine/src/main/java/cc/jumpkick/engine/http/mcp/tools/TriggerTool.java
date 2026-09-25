// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpAgentText;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpJobRuns;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.engine.jobs.JobSpec;
import java.util.Map;

/**
 * {@code jk_build} / {@code jk_test} / {@code jk_lock} — fire one job kind at a directory and
 * answer with its jid. One class, three registry lines: the tools differ only in the kind they
 * pin and the prose that explains it.
 */
public final class TriggerTool implements McpTool {

    private final Spec spec;
    private final String kind;

    public TriggerTool(String name, String kind, String description) {
        this.kind = kind;
        this.spec = new Spec(name, description, McpSchemas.dirOnly(McpSchemas.WORKSPACE_ROOT));
    }

    @Override
    public Spec spec() {
        return spec;
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        Map<String, Object> accepted = McpJobRuns.accept(in, JobSpec.of(kind, in.requiredDir()));
        long jid = accepted.get("jid") instanceof Number n ? n.longValue() : 0L;
        return in.ok(accepted, McpAgentText.running(kind, jid));
    }
}
