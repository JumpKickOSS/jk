// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.CacheSnapshot;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpContext;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpMachine;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.util.Map;
import java.util.function.Supplier;

/** {@code jk_disk} — cache vs store usage, and the two destructive actions behind {@code confirm}. */
public final class DiskTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "jk_disk",
                "Cache vs store disk usage. clean/nuke require confirm=true (nuke cache only).",
                McpSchemas.object(
                        Map.of("action", McpSchemas.string("usage | clean | nuke"), "confirm", McpSchemas.bool())));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        McpContext ctx = in.ctx();
        String action = in.action("usage");
        if ("usage".equals(action)) {
            return in.ok(McpEnvelope.of("disk", McpMachine.diskUsage(ctx.cacheSnapshot())), "disk usage");
        }
        boolean confirm = in.flag("confirm");
        Map<String, Object> data = McpMachine.diskAction(action, confirm, ctx.cacheGate());
        Supplier<CacheSnapshot> snapshot = ctx.cacheSnapshot();
        if (confirm && snapshot instanceof CacheSnapshot.Memoizing memo) {
            memo.invalidate(); // clean/nuke moved bytes; the next read must re-walk
        }
        return in.ok(McpEnvelope.of("disk", data), MachineActions.summary(data, action));
    }
}
