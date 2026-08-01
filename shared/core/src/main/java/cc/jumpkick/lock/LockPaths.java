// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Canonical location of the lockfile ({@code jk-lock.toml}).
 *
 * <ul>
 *   <li><strong>Workspace root or workspace member</strong> — always
 *       {@code <workspaceRoot>/jk-lock.toml} (one lock for the monorepo).
 *   <li><strong>Standalone project</strong> (not listed in any ancestor workspace) —
 *       {@code <projectDir>/jk-lock.toml}.
 * </ul>
 *
 * <p>Modules never own a lockfile. Paths not listed in {@code workspace.modules} are treated as
 * standalone even if nested under a workspace tree.
 */
public final class LockPaths {

    /** Filename only — always resolve via {@link #lockFile(Path)}. */
    public static final String FILE_NAME = "jk-lock.toml";

    private LockPaths() {}

    /**
     * Directory that owns the lockfile for {@code projectDir} (workspace root when inside a
     * workspace, else {@code projectDir} itself).
     */
    public static Path lockOwnerDir(Path projectDir) {
        Objects.requireNonNull(projectDir, "projectDir");
        Path dir = projectDir.toAbsolutePath().normalize();
        Path toml = dir.resolve("jk.toml");
        if (Files.isRegularFile(toml)) {
            try {
                JkBuild build = JkBuildParser.parseLocal(toml);
                if (build.isWorkspaceRoot()) {
                    return dir;
                }
            } catch (IOException | RuntimeException ignored) {
                // fall through to locator / standalone
            }
        }
        try {
            Optional<Path> root = WorkspaceLocator.findRoot(dir);
            if (root.isPresent()) {
                return root.get();
            }
        } catch (IOException ignored) {
            // treat as standalone
        }
        return dir;
    }

    /** Absolute path of the lockfile that governs {@code projectDir}. */
    public static Path lockFile(Path projectDir) {
        return lockOwnerDir(projectDir).resolve(FILE_NAME);
    }

    /**
     * True when locking/building {@code projectDir} is governed by an enclosing (or self)
     * workspace root lock rather than a standalone project lock.
     */
    public static boolean isWorkspaceLock(Path projectDir) {
        Path owner = lockOwnerDir(projectDir);
        Path dir = projectDir.toAbsolutePath().normalize();
        if (!owner.equals(dir)) {
            return true; // member → root
        }
        Path toml = owner.resolve("jk.toml");
        if (!Files.isRegularFile(toml)) {
            return false;
        }
        try {
            return JkBuildParser.parse(toml).isWorkspaceRoot();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
