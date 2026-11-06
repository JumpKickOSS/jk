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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code new} — the scaffolder behind {@code jk new} and the dashboard, preview included. A
 * template's questions are answered by {@code params}; what is still required goes to the human
 * through elicitation when the client offers it.
 */
public final class NewTool implements McpTool {

    @Override
    public Spec spec() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("description", "Template answers, key: value (action=form lists them)");
        return new Spec(
                "new",
                "Scaffold a project from a template (same as jk new; default template app). action=templates "
                        + "lists templates; action=form lists a template's fields; preview=true lists files "
                        + "without writing.",
                McpSchemas.object(Map.of(
                        "action",
                        McpSchemas.string("create (default) | templates | form | preview"),
                        "name",
                        McpSchemas.string("Project name and directory leaf (lowercase letters, digits, -)"),
                        "parentDir",
                        McpSchemas.string("Parent directory (default: parent of bound dir)"),
                        "group",
                        McpSchemas.string("Group id and package (default com.example)"),
                        "lang",
                        McpSchemas.string("java (default) | kotlin | groovy"),
                        "layout",
                        McpSchemas.string(Layout.scaffoldHelp() + " — where to place sources, not a jk.toml key"),
                        "template",
                        McpSchemas.string("Template id (java/spring-boot/webapp), framework/name, name (default app),"
                                + " or a directory, relative to parentDir (else the bound dir)"),
                        "params",
                        params,
                        "preview",
                        McpSchemas.bool("List files without writing"))));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        String action = in.action("create");
        try {
            if ("templates".equalsIgnoreCase(action)) {
                return in.ok(McpEnvelope.of("templates", McpScaffold.templates()), "template catalog");
            }
            if ("form".equalsIgnoreCase(action)) {
                Map<String, Object> form = McpScaffold.form(in.str("template"), in.str("lang"), cwd(in));
                return in.ok(McpEnvelope.of("new-form", form), "form of " + form.get("template"));
            }
            NewProjectOps.Request req = McpScaffold.request(in, parentDir(in), cwd(in));
            boolean preview = "preview".equalsIgnoreCase(action) || in.flag("preview");
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

    /**
     * Where a relative template path resolves, for the form and the scaffold alike: the
     * {@code parentDir} argument, else the bound dir, else the home directory.
     */
    private static Path cwd(McpCall in) {
        String parent = in.str("parentDir");
        if (parent != null && !parent.isBlank()) return Path.of(parent);
        String bound = in.dir();
        return Path.of(bound != null && !bound.isBlank() ? bound : System.getProperty("user.home"));
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
