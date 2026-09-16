// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.config.WorkspaceLocator;
import cc.jumpkick.config.WorkspaceScan;
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
 *   <li><strong>Shadowed project</strong> ({@code pom.xml}, no {@code jk.toml}) — beside the
 *       shadow manifest, {@code <projectDir>/target/jk/shadow/jk-lock.toml}, so the repository
 *       stays clean.
 * </ul>
 *
 * <p>Modules never own a lockfile. Paths not listed in {@code workspace.modules} are treated as
 * standalone even if nested under a workspace tree.
 */
public final class LockPaths {

    private LockPaths() {}

    /**
     * Directory that owns the lockfile for {@code projectDir} (workspace root when inside a
     * workspace, else {@code projectDir} itself).
     */
    public static Path lockOwnerDir(Path projectDir) {
        Objects.requireNonNull(projectDir, "projectDir");
        Path dir = projectDir.toAbsolutePath().normalize();
        if (ManifestPaths.isShadowed(dir)) {
            return ManifestPaths.shadowDir(dir);
        }
        Path toml = dir.resolve(ManifestPaths.MANIFEST);
        if (Files.isRegularFile(toml) && WorkspaceScan.isWorkspaceRoot(dir)) {
            return dir;
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
        return lockOwnerDir(projectDir).resolve(ManifestPaths.LOCK);
    }

    /**
     * True when locking/building {@code projectDir} is governed by an enclosing (or self)
     * workspace root lock rather than a standalone project lock.
     */
    public static boolean isWorkspaceLock(Path projectDir) {
        Path dir = projectDir.toAbsolutePath().normalize();
        if (ManifestPaths.isShadowed(dir)) {
            return false; // the shadow dir owns the lock, and it is no workspace
        }
        Path owner = lockOwnerDir(projectDir);
        if (!owner.equals(dir)) {
            return true; // member → root
        }
        Path toml = owner.resolve(ManifestPaths.MANIFEST);
        if (!Files.isRegularFile(toml)) {
            return false;
        }
        return WorkspaceScan.isWorkspaceRoot(owner);
    }
}
