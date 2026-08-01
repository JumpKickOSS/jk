// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Finds the workspace root that owns a module by walking ancestors for a {@code jk.toml} whose
 * {@code workspace.modules} lists that module.
 */
public final class WorkspaceLocator {

    /** Guard against symlink cycles or other pathological filesystems. */
    private static final int MAX_DEPTH = 8192;

    private WorkspaceLocator() {}

    /**
     * Nearest strict ancestor that is a workspace root (no membership check — for create/register).
     */
    public static Optional<Path> findEnclosingWorkspace(Path dir) throws IOException {
        Path normalized = dir.toAbsolutePath().normalize();
        Path candidate = normalized;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Path parent = candidate.getParent();
            if (parent == null) break; // reached filesystem root
            Path rootJkToml = parent.resolve("jk.toml");
            if (Files.exists(rootJkToml)) {
                try {
                    if (JkBuildParser.parseLocal(rootJkToml).isWorkspaceRoot()) {
                        return Optional.of(parent);
                    }
                } catch (RuntimeException ignored) {
                    // Unparseable ancestor manifest — keep walking up.
                }
            }
            candidate = parent;
        }
        return Optional.empty();
    }

    /**
     * Return the workspace root that owns {@code moduleDir}, or empty if {@code moduleDir} is not
     * inside a workspace.
     */
    public static Optional<Path> findRoot(Path moduleDir) throws IOException {
        Path normalized = moduleDir.toAbsolutePath().normalize();
        Path candidate = normalized;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Path parent = candidate.getParent();
            if (parent == null) break; // reached filesystem root
            Path rootJkToml = parent.resolve("jk.toml");
            if (Files.exists(rootJkToml)) {
                JkBuild root;
                try {
                    root = JkBuildParser.parseLocal(rootJkToml);
                } catch (RuntimeException ignored) {
                    candidate = parent;
                    continue;
                }
                if (root.isWorkspaceRoot()) {
                    String relative = parent.relativize(normalized).toString().replace('\\', '/');
                    if (root.workspace().modules().contains(relative)) {
                        return Optional.of(parent);
                    }
                }
            }
            candidate = parent;
        }
        return Optional.empty();
    }
}
