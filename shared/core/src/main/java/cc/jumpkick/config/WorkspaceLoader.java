// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads each {@code workspace.modules} entry's {@code jk.toml} (literal paths only). Missing
 * modules raise {@link JkBuildParseException}. Resolves Cargo-style {@code <field>.workspace
 * = true} against the workspace root before returning.
 */
public final class WorkspaceLoader {

    private WorkspaceLoader() {}

    public static Map<Path, JkBuild> loadModules(Path workspaceRoot, JkBuild root) throws IOException {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        Objects.requireNonNull(root, "root");
        if (!root.isWorkspaceRoot()) return Map.of();
        if (root.project().inheritsFromWorkspace()) {
            throw new JkBuildParseException("workspace root must set concrete project values"
                    + " (`*.workspace = true` is only valid on workspace modules)");
        }

        Map<Path, JkBuild> modules = new LinkedHashMap<>();
        List<String> bad = new ArrayList<>();
        for (String module : root.workspace().modules()) {
            Path moduleDir = workspaceRoot.resolve(module).normalize();
            Path moduleJkToml = moduleDir.resolve(ManifestPaths.MANIFEST);
            if (!Files.exists(moduleJkToml)) {
                bad.add(module);
                continue;
            }
            // parseLocal: avoid WorkspaceResolve recursion (loadModules is called from applyWorkspace).
            JkBuild moduleBuild = JkBuildParser.parseLocal(moduleJkToml);
            // Nested workspaces are forbidden (ambiguous shared target/).
            if (moduleBuild.isWorkspaceRoot()) {
                throw new JkBuildParseException("workspaces cannot be nested — `"
                        + module
                        + "` declares its own `[workspace]` block "
                        + "while being a module of `"
                        + workspaceRoot
                        + "`.");
            }
            modules.put(moduleDir, inheritFromRoot(moduleBuild, root));
        }
        if (!bad.isEmpty()) {
            throw new JkBuildParseException("workspace modules missing jk.toml: " + bad);
        }
        checkArtifactCollisions(workspaceRoot, root, modules);
        return modules;
    }

    /**
     * Apply every pending {@code *.workspace = true} field from the workspace root.
     * Unchanged when the module has no inheritance flags.
     */
    public static JkBuild inheritFromRoot(JkBuild module, JkBuild root) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(root, "root");
        if (!module.project().inheritsFromWorkspace()) return module;
        try {
            return module.withProject(module.project().resolveFromWorkspaceRoot(root.project()));
        } catch (IllegalArgumentException e) {
            throw new JkBuildParseException("module `" + module.project().name() + "`: " + e.getMessage(), e);
        }
    }

    /** @deprecated use {@link #inheritFromRoot} */
    @Deprecated
    public static JkBuild inheritVersionFromRoot(JkBuild module, JkBuild root) {
        return inheritFromRoot(module, root);
    }

    /**
     * Final artifacts land in a shared {@code <workspaceRoot>/target/} directory keyed by {@code
     * <artifact>-<version>.jar} — so two modules declaring the same artifact + version would race to
     * write the same jar and the second one would silently win. Reject at workspace-parse time so the
     * failure is loud and the file paths in the error point at the conflict.
     *
     * <p>Modules and the workspace root itself can collide (a workspace root that's <i>also</i> a
     * runnable project is rare but legal, so we include the root in the uniqueness set).
     */
    private static void checkArtifactCollisions(Path workspaceRoot, JkBuild root, Map<Path, JkBuild> modules) {
        Map<String, Path> claimed = new LinkedHashMap<>();
        record Entry(Path dir, JkBuild build) {}
        List<Entry> all = new ArrayList<>(modules.size() + 1);
        // Only include the root if it could plausibly produce its own jar
        // (i.e., it declares a non-blank artifact). Many workspace roots
        // are pure coordinators with no own artifact; skip those.
        if (!root.project().name().isBlank()) {
            all.add(new Entry(null, root));
        }
        for (Map.Entry<Path, JkBuild> e : modules.entrySet()) {
            all.add(new Entry(e.getKey(), e.getValue()));
        }
        for (Entry e : all) {
            String key = e.build.project().name() + "-" + e.build.project().version();
            // containsKey, not the put return value: the workspace root
            // stores `null` as its dir, and Map.put can't distinguish a
            // returned null between "no prior entry" and "prior entry's
            // value was null".
            if (claimed.containsKey(key)) {
                Path previous = claimed.get(key);
                String prevLabel = moduleLabel(workspaceRoot, previous);
                String thisLabel = moduleLabel(workspaceRoot, e.dir);
                throw new JkBuildParseException("workspace artifact collision: `"
                        + key
                        + ".jar` would be "
                        + "produced by both `"
                        + prevLabel
                        + "` and `"
                        + thisLabel
                        + "`. Final artifacts share <workspaceRoot>/target/, so two "
                        + "modules can't emit the same `<artifact>-<version>.jar`. "
                        + "Differentiate via the modules' name or version.");
            }
            claimed.put(key, e.dir);
        }
    }

    /**
     * Workspace-relative path with {@code /} separators, or {@code <workspace root>} for the root's
     * own artifact.
     *
     * <p>A module declared with an absolute path (or, on Windows, one on another drive) is labelled
     * with its full path: {@code relativize} throws {@link IllegalArgumentException} when the two
     * paths differ in absoluteness or root, and a collision message must not become a crash.
     */
    private static String moduleLabel(Path workspaceRoot, Path moduleDir) {
        if (moduleDir == null) return "<workspace root>";
        Path label = moduleDir.startsWith(workspaceRoot) ? workspaceRoot.relativize(moduleDir) : moduleDir;
        return label.toString().replace('\\', '/');
    }
}
