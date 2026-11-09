// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.changes.Change;
import cc.jumpkick.changes.ChangeJournal;
import cc.jumpkick.engine.http.CacheSnapshot;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpContext;
import cc.jumpkick.engine.http.mcp.McpDecisions;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpMachine;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.engine.plugin.WorkerLeases;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/** {@code disk} — cache vs store usage, and the two destructive actions behind {@code confirm}. */
public final class DiskTool implements McpTool {

    /** The question each destructive action puts to a host that can ask. */
    private static final Map<String, String> QUESTIONS = Map.of(
            "clean", "Clean jk's cache (disk clean)?",
            "nuke", "Delete jk's whole cache tier (disk nuke)?",
            "m2-clean", "Remove the files jk downloaded into ~/.m2 and has not used lately (disk m2-clean)?");

    private static final String YES = "Yes, delete them";

    @Override
    public Spec spec() {
        return new Spec(
                "disk",
                "Cache vs store disk usage. clean/nuke/m2-clean require confirm=true, and a host that can ask the user asks (nuke cache only; m2-clean"
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
        if (confirm && QUESTIONS.containsKey(action)) {
            Map<String, Object> stop = McpDecisions.confirm(in, this, "disk-" + action, QUESTIONS.get(action), YES);
            if (stop != null) return stop;
        }
        if ("m2-clean".equals(action)) {
            String dir = in.dir();
            Map<String, Object> m2 = McpMachine.m2Clean(
                    confirm,
                    in.count("olderThanDays", 0, 0, 36_500),
                    dir == null ? null : Path.of(dir),
                    ctx.cacheGate());
            noteDone(action, m2);
            return in.ok(McpEnvelope.of("disk", m2), MachineActions.summary(m2, action));
        }
        Map<String, Object> data = McpMachine.diskAction(action, confirm, ctx.cacheGate());
        Supplier<CacheSnapshot> snapshot = ctx.cacheSnapshot();
        if (confirm && snapshot instanceof CacheSnapshot.Memoizing memo) {
            memo.invalidate(); // clean/nuke moved bytes; the next read must re-walk
        }
        noteDone(action, data);
        return in.ok(McpEnvelope.of("disk", data), MachineActions.summary(data, action));
    }

    /** Journal an action that ran: a preview, an error or a clean that removed nothing changed nothing. */
    static void noteDone(String action, Map<String, Object> data) {
        if (data.containsKey("error") || Boolean.TRUE.equals(data.get("preview"))) return;
        long files = data.get("files") instanceof Number n ? n.longValue() : -1;
        long bytes = data.get("bytes") instanceof Number n ? n.longValue() : -1;
        String counted =
                files < 0 ? "" : ": " + files + " files, " + WorkerLeases.format(Math.max(0, bytes)) + " freed";
        switch (action) {
            case "nuke" -> {
                if (Boolean.TRUE.equals(data.get("nuked"))) {
                    ChangeJournal.note("cache tier", Change.DELETE, "nuked the cache tier", null);
                }
            }
            case "clean" -> {
                if (files != 0)
                    ChangeJournal.note("cache tier", Change.CLEAN, "cleaned the cache tier" + counted, null);
            }
            case "m2-clean" -> {
                if (files != 0) {
                    ChangeJournal.note(
                            "Maven local repository",
                            Change.CLEAN,
                            "cleaned JumpKick's unused downloads from ~/.m2" + counted,
                            null);
                }
            }
            default -> {}
        }
    }
}
