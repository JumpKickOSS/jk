// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.builds;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TomlScan;
import cc.jumpkick.config.WorkspaceModules;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Best-effort collection of declared Maven dependency keys from a project or workspace root
 * {@code jk.toml} tree. Path/git/workspace/file deps are skipped; keys prefer catalog short names.
 */
public final class DeclaredDeps {

    private DeclaredDeps() {}

    /**
     * Union of declared Maven deps from the root manifest and each workspace module. Failures
     * parsing individual files are skipped.
     */
    public static Set<String> collect(@Nullable Path projectOrWorkspaceRoot) {
        if (projectOrWorkspaceRoot == null) return Set.of();
        Path root = projectOrWorkspaceRoot.toAbsolutePath().normalize();
        Path rootToml = root.resolve(ManifestPaths.MANIFEST);
        if (!Files.isRegularFile(rootToml)) return Set.of();

        LibraryCatalog catalog;
        try {
            catalog = LibraryCatalog.forProject(root);
        } catch (RuntimeException e) {
            catalog = LibraryCatalog.layered();
        }

        TreeSet<String> out = new TreeSet<>();
        collectFromToml(rootToml, catalog, out);
        for (Path moduleDir : moduleDirs(root, rootToml)) {
            Path mt = moduleDir.resolve(ManifestPaths.MANIFEST);
            if (Files.isRegularFile(mt)) collectFromToml(mt, catalog, out);
        }
        return Set.copyOf(out);
    }

    /** Prefer catalog short name, else reverse-mapped name, else lowercase {@code group:artifact}. */
    public static String normalize(Dependency d, LibraryCatalog catalog) {
        if (d == null) return "";
        LibraryCatalog cat = catalog == null ? LibraryCatalog.layered() : catalog;
        if (d.library() != null
                && !d.library().isBlank()
                && cat.lookup(d.library()).isPresent()) {
            return d.library();
        }
        return cat.nameForModule(d.module())
                .orElseGet(() -> d.module() == null ? "" : d.module().toLowerCase(Locale.ROOT));
    }

    private static void collectFromToml(Path toml, LibraryCatalog catalog, TreeSet<String> out) {
        try {
            JkBuild build = JkBuildParser.parse(toml);
            for (var e : build.dependencies().byScope().entrySet()) {
                addDeps(e.getValue(), catalog, out);
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private static void addDeps(List<Dependency> deps, LibraryCatalog catalog, TreeSet<String> out) {
        if (deps == null) return;
        for (Dependency d : deps) {
            if (d == null) continue;
            if (d.isPath() || d.isGit() || d.isWorkspace() || d.isFile()) continue;
            String key = normalize(d, catalog);
            if (!key.isBlank()) out.add(key);
        }
    }

    /** Module directories listed under {@code [workspace] modules}, relative to {@code root}. */
    private static List<Path> moduleDirs(Path root, Path rootToml) {
        List<String> rels = List.of();
        try {
            JkBuild rootBuild = JkBuildParser.parse(rootToml);
            if (rootBuild.isWorkspaceRoot()) {
                rels = rootBuild.workspaceModules();
            }
        } catch (Exception e) {
            TomlScan scan = TomlScan.scan(rootToml, "workspace.modules");
            rels = scan.stringArray("workspace.modules");
        }
        if (rels.isEmpty()) {
            TomlScan scan = TomlScan.scan(rootToml, "workspace.modules");
            List<String> scanned = scan.stringArray("workspace.modules");
            if (!scanned.isEmpty()) rels = scanned;
        }
        List<Path> dirs = new ArrayList<>(rels.size());
        for (String rel : expandQuietly(root, rels)) {
            if (rel == null || rel.isBlank()) continue;
            dirs.add(root.resolve(rel).normalize());
        }
        return dirs;
    }

    /** Globs expanded; a pattern that matches nothing maps no changes rather than failing a scan. */
    private static List<String> expandQuietly(Path root, List<String> rels) {
        try {
            return WorkspaceModules.expand(root, rels);
        } catch (RuntimeException e) {
            return rels.stream()
                    .filter(r -> r != null && !WorkspaceModules.isGlob(r))
                    .toList();
        }
    }
}
