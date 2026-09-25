// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.ide.IdeGeneration;
import cc.jumpkick.ide.IdeTarget;
import cc.jumpkick.runtime.base.IdeFilesOps;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** {@code ide} — the generators behind {@code jk ide}, run engine-side; preview lists without writing. */
public final class IdeTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "ide",
                "Write IDE project files (same generators as jk ide): kind=idea (.idea + *.iml), "
                        + "vscode (.vscode + Eclipse metadata for redhat.java) or all (default); "
                        + ".bsp/jk.json is refreshed too. Returns the files written; preview=true "
                        + "lists them without writing. Missing jars are fetched first; run lock "
                        + "after a jk.toml change.",
                McpSchemas.object(Map.of(
                        "kind",
                        McpSchemas.string("idea | vscode | all (default)"),
                        "dir",
                        McpSchemas.string(McpSchemas.BOUND_ROOT),
                        "preview",
                        McpSchemas.bool("List files without writing"))));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        Path root = PathUtil.resolveUserPath(in.requiredDir());
        String kind = kind(in.str("kind"));
        boolean preview = in.flag("preview");
        IdeFilesOps.Result result = IdeFilesOps.generate(root, JkDirs.cache(), null, null, targets(kind), preview);

        Map<String, Object> m = new LinkedHashMap<>();
        if (result.error() != null) {
            m.put("error", result.error());
            return in.ok(McpEnvelope.of("ide", m), "ide failed");
        }
        m.put("kind", kind);
        m.put("wsRoot", result.wsRoot());
        m.put("rootName", result.rootName());
        List<String> files = new ArrayList<>();
        List<String> sdkTables = new ArrayList<>();
        for (IdeGeneration g : result.generations()) {
            for (Path p : g.files()) files.add(p.toString());
            for (Path p : g.sdkTables()) sdkTables.add(p.toString());
        }
        Path bsp = result.bsp();
        if (bsp != null) files.add(bsp.toString());
        m.put("files", files);
        if (!sdkTables.isEmpty()) m.put("sdkTables", sdkTables);
        if (preview) {
            return in.ok(
                    McpEnvelope.of(
                            "ide-preview", m, false, null, "Nothing was written — call again without preview to write"),
                    "ide preview: " + files.size() + " files");
        }
        return in.ok(
                McpEnvelope.of("ide", m, false, null, "Restart the IDE so it picks up the new project files"),
                "wrote " + files.size() + " IDE files");
    }

    private static String kind(@Nullable String raw) {
        String k = raw == null || raw.isBlank() ? "all" : raw.trim().toLowerCase(Locale.ROOT);
        if (!k.equals("idea") && !k.equals("vscode") && !k.equals("all")) {
            throw new McpError(-32602, "kind must be idea | vscode | all");
        }
        return k;
    }

    private static Set<IdeTarget> targets(String kind) {
        return switch (kind) {
            case "idea" -> EnumSet.of(IdeTarget.IDEA);
            case "vscode" -> EnumSet.of(IdeTarget.VSCODE);
            default -> EnumSet.allOf(IdeTarget.class);
        };
    }
}
