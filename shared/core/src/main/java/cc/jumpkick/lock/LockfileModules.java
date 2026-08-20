// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.VersionSelector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Captures resolved first-party project identity into {@link Lockfile.ModuleEntry} rows for
 * {@code jk-lock.toml}. Inheritance ({@code *.workspace = true}) is already applied by
 * {@link WorkspaceLoader} before capture, so the lock always stores concrete values.
 */
public final class LockfileModules {

    private LockfileModules() {}

    /**
     * Stamp {@code lock} with module pins for the project/workspace that owns {@code projectDir}
     * (the directory of the {@code jk.toml} being locked, which may be a workspace root, member, or
     * standalone project). Best-effort: on parse failure returns {@code lock} unchanged.
     */
    public static Lockfile stamp(Lockfile lock, Path projectDir) {
        try {
            return lock.withModules(capture(projectDir));
        } catch (Exception e) {
            return lock;
        }
    }

    /**
     * Resolved module pins for {@code projectDir}. Workspace → root {@code "."} plus each member
     * path; standalone → a single {@code "."} row.
     */
    public static List<Lockfile.ModuleEntry> capture(Path projectDir) throws IOException {
        Path dir = projectDir.toAbsolutePath().normalize();
        Path toml = dir.resolve("jk.toml");
        if (!Files.isRegularFile(toml)) return List.of();
        // parseLocal + explicit workspace capture (resolved pins), not parse() which also rewrites
        // sibling deps we do not need here.
        JkBuild parsed = JkBuildParser.parseLocal(toml);

        if (parsed.isWorkspaceRoot()) {
            return captureWorkspace(dir, parsed);
        }
        var rootOpt = WorkspaceLocator.findRoot(dir);
        if (rootOpt.isPresent()) {
            Path root = rootOpt.get();
            JkBuild rootManifest = JkBuildParser.parseLocal(root.resolve("jk.toml"));
            return captureWorkspace(root, rootManifest);
        }
        // Standalone: drop optional auto-inherits so we pin concrete local defaults.
        if (parsed.project().inheritsFromWorkspace()) {
            parsed = parsed.withProject(parsed.project().droppingOptionalInherits());
        }
        return List.of(fromProject(".", parsed.project()));
    }

    private static List<Lockfile.ModuleEntry> captureWorkspace(Path rootDir, JkBuild root) throws IOException {
        List<Lockfile.ModuleEntry> out = new ArrayList<>();
        out.add(fromProject(".", root.project()));
        Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(rootDir, root);
        for (var e : modules.entrySet()) {
            String rel = rootDir.relativize(e.getKey()).toString().replace('\\', '/');
            if (rel.isEmpty()) rel = ".";
            out.add(fromProject(rel, e.getValue().project()));
        }
        return out;
    }

    /** Build a lock pin from an already-resolved project (no pending workspace inherits). */
    public static Lockfile.ModuleEntry fromProject(String path, JkBuild.Project p) {
        String sources =
                switch (p.sourcesMode()) {
                    case DISABLED -> null;
                    case PUBLISH -> "publish";
                    case ALWAYS -> "always";
                };
        return new Lockfile.ModuleEntry(
                path,
                p.group(),
                p.name(),
                p.version(),
                p.jdk(),
                p.java() > 0 ? p.java() : null,
                selectorRaw(p.kotlin()),
                selectorRaw(p.groovy()),
                p.description(),
                sources,
                p.m2install() ? Boolean.TRUE : null,
                null);
    }

    private static String selectorRaw(VersionSelector v) {
        return v == null ? null : v.raw();
    }
}
