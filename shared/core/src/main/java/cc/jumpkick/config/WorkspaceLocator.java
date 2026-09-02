// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Finds the workspace root that owns a module by walking ancestors for a {@code jk.toml} whose
 * {@code workspace.modules} lists that module.
 *
 * <p>Delegates to {@link WorkspaceScan} (bootstrap TOML only). A full {@code JkBuildParser} walk
 * would pull plugin schemas onto every CLI path that locates a lockfile.
 */
public final class WorkspaceLocator {

    private WorkspaceLocator() {}

    /**
     * Nearest strict ancestor that is a workspace root (no membership check — for create/register).
     */
    public static Optional<Path> findEnclosingWorkspace(Path dir) throws IOException {
        return WorkspaceScan.findEnclosingWorkspace(dir);
    }

    /**
     * Return the workspace root that owns {@code moduleDir}, or empty if {@code moduleDir} is not
     * inside a workspace.
     */
    public static Optional<Path> findRoot(Path moduleDir) throws IOException {
        return WorkspaceScan.findRoot(moduleDir);
    }
}
