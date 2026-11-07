// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.HeldFiles;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpDecisions;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpManifest;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.runtime.base.PolicyOps;
import cc.jumpkick.wire.protocol.DenyReport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code deps} — add, remove, or pin dependencies in {@code jk.toml}, then relock. {@code
 * preview=true} writes nothing and does not lock; an added or pinned version must still exist in the
 * configured repositories. An add whose lock trips the project's deny policy is put back, and
 * kept only when the user accepts it as a trust-changing decision.
 */
public final class DepsTool implements McpTool {

    static final String KIND = "deps-add";

    static final String DESCRIPTION =
            "Add, remove, or pin coords in jk.toml and relock. action=add, remove, or pin; preview=true does not write.";

    @Override
    public Spec spec() {
        return new Spec(
                "deps",
                DESCRIPTION,
                McpSchemas.withGoal(
                        Map.of(
                                "action",
                                McpSchemas.oneOf("add", "remove", "pin"),
                                "coords",
                                McpSchemas.strings(),
                                "scope",
                                McpSchemas.string(),
                                "dir",
                                McpSchemas.string(),
                                "preview",
                                McpSchemas.bool()),
                        List.of()));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        String action = in.action("add").toLowerCase(Locale.ROOT);
        if (!action.equals("add") && !action.equals("remove") && !action.equals("pin")) {
            throw new McpError(-32602, "deps action must be add, remove, or pin");
        }
        boolean preview = in.flag("preview");
        String dir = in.requiredDir();
        boolean continued = "add".equals(action) && !preview && in.str(McpDecisions.DECISION) != null;
        if (continued) {
            McpDecisions.Outcome outcome = McpDecisions.require(in, this, denied(dir, List.of()));
            if (outcome instanceof McpDecisions.Outcome.Stop stop) return stop.result();
        }
        return edit(in, action, dir, preview, "add".equals(action) && !continued);
    }

    /** The edit and relock; with {@code gate}, an add whose lock trips the deny policy is put back and asked. */
    private Map<String, Object> edit(McpCall in, String action, String dir, boolean preview, boolean gate) {
        Path root = PathUtil.resolveUserPath(dir);
        HeldFiles held;
        try {
            held = HeldFiles.of(List.of(ManifestPaths.manifestIn(root), LockPaths.lockFile(root)));
        } catch (IOException e) {
            return in.error(Errors.text(e) + "\n");
        }
        List<String> already = gate && !preview ? denyHits(root) : List.of();
        Map<String, Object> data = McpManifest.deps(dir, action, in.strings("coords"), in.str("scope"), !preview);
        if (data.get("error") != null) {
            return in.error(data.get("error") + "\n");
        }
        String changed = DepsLock.changedLine(data);
        if (preview || !Boolean.TRUE.equals(data.get("applied"))) {
            String text = preview ? changed + "\npreview\n" : changed + "\n";
            return in.text(text);
        }
        String lock = DepsLock.relock(root);
        String text = changed + "\n" + lock + "\n";
        if (!"lock ok".equals(lock)) return in.error(text);
        List<String> hits = new ArrayList<>(gate ? denyHits(root) : List.of());
        hits.removeAll(already);
        if (hits.isEmpty()) return in.text(text);
        try {
            held.restore();
        } catch (IOException e) {
            return in.error(text + "could not put jk.toml back: " + Errors.text(e) + "\n");
        }
        McpDecisions.Outcome outcome = McpDecisions.require(in, this, denied(dir, hits));
        if (outcome instanceof McpDecisions.Outcome.Stop stop) return stop.result();
        return edit(in, action, dir, preview, false);
    }

    /** What the deny policy says about the lock {@code root} now has, one line per hit. */
    private static List<String> denyHits(Path root) {
        DenyReport report = PolicyOps.denyCheck(root);
        List<String> hits = new ArrayList<>();
        for (int i = 0; i < report.violationCount(); i++) {
            hits.add(report.modules().get(i) + " " + report.versions().get(i) + ": "
                    + report.reasons().get(i));
        }
        return hits;
    }

    private static McpDecisions.Ask denied(String dir, List<String> hits) {
        return new McpDecisions.Ask(
                KIND,
                "Add dependencies that the deny policy refuses in " + dir + "?",
                hits,
                "Yes, add them anyway",
                true);
    }
}
