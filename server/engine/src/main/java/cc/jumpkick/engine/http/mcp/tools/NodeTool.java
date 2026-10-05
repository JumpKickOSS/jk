// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.InstalledTool;
import cc.jumpkick.config.NodePinEdit;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpEnvelope;
import cc.jumpkick.engine.http.mcp.McpSchemas;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.NodeShape;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.node.NodeCatalog;
import cc.jumpkick.node.NodeInstalls;
import cc.jumpkick.node.NodeRelease;
import cc.jumpkick.node.NodeSpec;
import cc.jumpkick.runtime.base.CompatPlans;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code node} — list the Node.js installs or releases, install one, say which the project uses, or
 * pin the project and relock. {@code uninstall} needs {@code confirm}.
 */
public final class NodeTool implements McpTool {

    @Override
    public Spec spec() {
        return new Spec(
                "node",
                "List, install, uninstall or pin Node.js; which tells the project's. uninstall requires confirm=true.",
                McpSchemas.object(Map.of(
                        "action",
                        McpSchemas.string("list (default) | list-remote | install | uninstall | which | pin"),
                        "spec",
                        McpSchemas.string("24 / =24.21.0 / lts / latest; uninstall takes the exact version"),
                        "lts",
                        McpSchemas.bool("list-remote: LTS releases only"),
                        "major",
                        McpSchemas.integer("list-remote: every release of this major"),
                        "dir",
                        McpSchemas.string("which / pin: the project"),
                        "confirm",
                        McpSchemas.bool())));
    }

    @Override
    public Map<String, Object> call(McpCall in) {
        String action = in.action("list");
        Map<String, Object> data;
        try {
            data = switch (action) {
                case "list" -> list();
                case "list-remote" -> listRemote(in.flag("lts"), in.intOrNull("major"));
                case "install" -> install(in.str("spec"));
                case "uninstall" -> uninstall(in.str("spec"), in.flag("confirm"));
                case "which" -> which(Path.of(in.requiredDir()));
                case "pin" -> pin(Path.of(in.requiredDir()), in.str("spec"));
                default -> Map.of("error", "unknown action `" + action + "`");
            };
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            data = Map.of("error", Errors.text(e));
        }
        return in.ok(McpEnvelope.of("node", data), MachineActions.summary(data, action));
    }

    static Map<String, Object> list() throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (NodeInstalls.Install i : new NodeInstalls().all()) rows.add(install(i));
        return Map.of("installs", rows);
    }

    static Map<String, Object> listRemote(boolean lts, @Nullable Integer major)
            throws IOException, InterruptedException {
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (NodeRelease r : new NodeCatalog().releases()) {
            if (r.preRelease() || (lts && r.lts() == null)) continue;
            if (major != null ? r.major() != major : !seen.add(r.major())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("version", r.version());
            if (r.lts() != null) row.put("lts", r.lts());
            if (r.npm() != null) row.put("npm", r.npm());
            rows.add(row);
        }
        return Map.of("releases", rows);
    }

    static Map<String, Object> install(@Nullable String spec) {
        var p = CompatPlans.provisionTool(
                BuildTool.NODE.slug(), spec == null || spec.isBlank() ? "lts" : spec, JkDirs.tools(), false, false);
        if (p.error() != null) return Map.of("error", p.error());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", String.valueOf(p.version()));
        out.put("home", String.valueOf(p.bin()));
        out.put("source", String.valueOf(p.source()));
        return out;
    }

    static Map<String, Object> uninstall(@Nullable String version, boolean confirm) throws IOException {
        return uninstall(new NodeInstalls(), version, confirm);
    }

    /** As {@link #uninstall(String, boolean)}, against {@code installs}. */
    static Map<String, Object> uninstall(NodeInstalls installs, @Nullable String version, boolean confirm)
            throws IOException {
        if (version == null || version.isBlank()) return Map.of("error", "uninstall needs spec = the exact version");
        String v = version.startsWith("v") ? version.substring(1) : version;
        Optional<InstalledTool> installed = installs.managedTool(BuildTool.NODE, v);
        if (installed.isEmpty()) return Map.of("error", "Node.js " + v + " is not a jk install");
        if (!confirm)
            return Map.of(
                    "preview",
                    true,
                    "version",
                    v,
                    "home",
                    installed.get().home().toString());
        PathUtil.deleteRecursively(installed.get().home());
        return Map.of("removed", v);
    }

    static Map<String, Object> which(Path dir) throws IOException {
        Optional<NodeInstalls.ProjectNode> project = NodeInstalls.project(dir);
        if (project.isEmpty() || project.get().lookup() == null) {
            return Map.of("error", "no Node.js declared for " + dir);
        }
        NodeSpec spec = project.get().lookup();
        List<NodeRelease> releases = NodeInstalls.needsReleases(spec) ? releasesOrNone() : List.of();
        Optional<NodeInstalls.Install> found = new NodeInstalls().installed(spec, releases);
        if (found.isEmpty()) {
            return Map.of("wanted", project.get().wanted(), "installed", false);
        }
        Map<String, Object> out = install(found.get());
        out.put("wanted", project.get().wanted());
        out.put("installed", true);
        return out;
    }

    static Map<String, Object> pin(Path dir, @Nullable String spec) throws IOException {
        Optional<Path> target = NodeInstalls.nearestManifestDir(dir);
        if (target.isEmpty()) return Map.of("error", "no jk.toml at or above " + dir);
        String value;
        if (spec != null && !spec.isBlank()) {
            value = NodePinEdit.tomlValue(spec);
        } else {
            NodeShape.Proposal p = NodeShape.propose(target.get());
            int lts = NodeInstalls.newestLtsMajor(releasesOrNone());
            value = p.source() != null || lts == 0 ? p.spec() : Integer.toString(lts);
        }
        if (ManifestPaths.isShadowed(target.get()))
            return Map.of("error", ManifestPaths.noManifestToEdit(target.get().toString()));
        Path manifest = ManifestPaths.manifestIn(target.get());
        String text = Files.readString(manifest, StandardCharsets.UTF_8);
        Files.writeString(manifest, NodePinEdit.apply(text, value), StandardCharsets.UTF_8);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("manifest", manifest.toString());
        out.put("node", value);
        out.put("lock", DepsLock.relock(LockPaths.lockOwnerDir(target.get())));
        return out;
    }

    private static Map<String, Object> install(NodeInstalls.Install i) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("version", i.version());
        row.put("source", i.source());
        row.put("home", i.home().toString());
        return row;
    }

    private static List<NodeRelease> releasesOrNone() {
        try {
            return new NodeCatalog().releases();
        } catch (IOException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }
}
