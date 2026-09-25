// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpScaffold;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.engine.runtime.NewProjectOps;
import cc.jumpkick.host.Errors;
import cc.jumpkick.model.Layout;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** {@code new} — the scaffolder behind {@code jk new} and the dashboard, preview included. */
public final class NewTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "new",
                "Scaffold a project (same scaffolder as jk new / the dashboard). "
                        + "action=templates lists catalog + local template short names; "
                        + "preview=true returns the exact file set without writing.",
                McpSchemas.object(Map.of(
                        "action",
                        McpSchemas.string("create (default) | templates | preview"),
                        "name",
                        McpSchemas.string("Project name (letters, digits, . _ -)"),
                        "parentDir",
                        McpSchemas.string("Parent directory (default: parent of bound dir)"),
                        "group",
                        McpSchemas.string("Group id (default com.example)"),
                        "lang",
                        McpSchemas.string("java (default) | kotlin | groovy"),
                        "layout",
                        McpSchemas.string(Layout.scaffoldHelp() + " — where to place sources, not a jk.toml key"),
                        "template",
                        McpSchemas.string("Giter8 id (java/spring-boot/hello), framework/name, or name under none"),
                        "preview",
                        McpSchemas.bool("List files without writing"))));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        String action = in.action("create");
        if ("templates".equalsIgnoreCase(action)) {
            return in.ok(McpEnvelope.of("templates", McpScaffold.templates()), "template catalog");
        }
        NewProjectOps.Request req = new NewProjectOps.Request(
                in.str("name"),
                parentDir(in),
                in.str("group"),
                in.str("lang"),
                in.str("layout"),
                in.str("template"),
                in.flagOr("executable", true));
        boolean preview = "preview".equalsIgnoreCase(action) || in.flag("preview");
        try {
            if (preview) {
                return in.ok(
                        McpEnvelope.of(
                                "new-preview",
                                McpScaffold.preview(req),
                                false,
                                null,
                                "Nothing was written — call again without preview to create"),
                        "new preview");
            }
            Map<String, Object> created = McpScaffold.create(req);
            return in.ok(
                    McpEnvelope.of("created", created, false, null, "bind {dir: " + created.get("path") + "} next"),
                    "created " + created.get("path"));
        } catch (IllegalArgumentException e) {
            throw new McpError(-32602, e.getMessage());
        } catch (IllegalStateException e) {
            throw new McpError(-32000, e.getMessage());
        } catch (IOException e) {
            throw new McpError(-32000, Errors.text(e));
        }
    }

    /** Default scaffold parent: explicit arg, else the bound dir's parent. */
    private static String parentDir(McpCall in) {
        String parent = in.str("parentDir");
        if (parent != null && !parent.isBlank()) return parent;
        String bound = in.dir();
        if (bound != null && !bound.isBlank()) {
            Path p = Path.of(bound).getParent();
            if (p != null) return p.toString();
        }
        throw new McpError(-32602, "requires arguments.parentDir (or bind first — its parent is the default)");
    }
}
