// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpJobRuns;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.engine.verbs.ManifestEdit;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.lock.ManifestPaths;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code deps} — add, remove, or pin dependencies in {@code jk.toml}, then relock; {@code
 * action=lock} relocks without an edit, as the {@code lock} job {@code jk lock} runs. {@code
 * preview=true} writes nothing and does not lock; an added or pinned version must still exist in the
 * configured repositories. An add whose lock trips the project's deny policy fails naming each rule
 * it trips, and {@code jk.toml} and {@code jk-lock.toml} are put back as they were: the one
 * {@link ManifestEdit} {@code jk add} runs.
 */
public final class DepsTool implements McpTool {

    static final String DESCRIPTION =
            "Add, remove, or pin coords in jk.toml and relock; action=lock only relocks. preview=true does not write.";

    @Override
    public Spec spec() {
        return new Spec(
                "deps",
                DESCRIPTION,
                McpSchemas.withGoal(
                        Map.of(
                                "action",
                                McpSchemas.oneOf("add", "remove", "pin", "lock"),
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
        if ("lock".equals(action)) return lock(in);
        ManifestEdit.Request req;
        try {
            req = new ManifestEdit.Request(
                    ManifestPaths.manifestIn(PathUtil.resolveUserPath(in.requiredDir())),
                    ManifestEdit.coordOps(action, in.strings("coords"), in.str("scope")),
                    in.flag("preview"),
                    true);
        } catch (IllegalArgumentException e) {
            throw new McpError(-32602, "deps: " + e.getMessage());
        }
        return render(in, ManifestEdit.run(req), req.preview());
    }

    /** The {@code lock} job, waited on as {@code build} waits; an edit's arguments are refused by name. */
    private static Map<String, Object> lock(McpCall in) {
        return McpJobRuns.run(in, "lock");
    }

    /**
     * The agent's text for one edit: what {@code jk.toml} gained or lost, then {@code preview}, the
     * relock line, or the refusal; an error or a failed lock is {@code isError}.
     */
    static Map<String, Object> render(McpCall in, ManifestEdit.Result result, boolean preview) {
        if (result.error() != null) return in.error(result.error() + "\n");
        String changed = result.notes().isEmpty()
                ? (result.changed() ? "edited jk.toml" : "unchanged")
                : String.join("; ", result.notes());
        if (preview) return in.text(changed + "\npreview\n");
        if (result.lock() == null) return in.text(changed + "\n");
        String text = changed + "\n" + result.lock() + "\n";
        return result.lockOk() ? in.text(text) : in.error(text);
    }
}
