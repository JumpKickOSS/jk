// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.CacheSnapshot;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpContext;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpMachine;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/** {@code disk} — cache vs store usage, and the two destructive actions behind {@code confirm}. */
public final class DiskTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "disk",
                "Cache vs store disk usage. clean/nuke/m2-clean require confirm=true (nuke cache only; m2-clean"
                        + " removes only files jk downloaded into ~/.m2 and has not used for olderThanDays,"
                        + " keeping what dir's lockfile pins; without confirm it previews).",
                McpSchemas.object(Map.of(
                        "action",
                        McpSchemas.string("usage | clean | nuke | m2-clean"),
                        "confirm",
                        McpSchemas.bool(),
                        "olderThanDays",
                        McpSchemas.integer("m2-clean: days unused (default: [m2] clean-after-days)"))));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        McpContext ctx = in.ctx();
        String action = in.action("usage");
        if ("usage".equals(action)) {
            return in.ok(McpEnvelope.of("disk", McpMachine.diskUsage(ctx.cacheSnapshot())), "disk usage");
        }
        boolean confirm = in.flag("confirm");
        if ("m2-clean".equals(action)) {
            String dir = in.dir();
            Map<String, Object> m2 = McpMachine.m2Clean(
                    confirm,
                    in.count("olderThanDays", 0, 0, 36_500),
                    dir == null ? null : Path.of(dir),
                    ctx.cacheGate());
            return in.ok(McpEnvelope.of("disk", m2), MachineActions.summary(m2, action));
        }
        Map<String, Object> data = McpMachine.diskAction(action, confirm, ctx.cacheGate());
        Supplier<CacheSnapshot> snapshot = ctx.cacheSnapshot();
        if (confirm && snapshot instanceof CacheSnapshot.Memoizing memo) {
            memo.invalidate(); // clean/nuke moved bytes; the next read must re-walk
        }
        return in.ok(McpEnvelope.of("disk", data), MachineActions.summary(data, action));
    }
}
