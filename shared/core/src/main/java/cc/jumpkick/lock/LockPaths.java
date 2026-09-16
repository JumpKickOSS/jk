// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.config.PomReactorScan;
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
 * <p>{@link #lockOwnerDir} names the project directory whose manifest the lock is derived from:
 * the workspace root for a root or a member, the project itself when standalone. A directory built
 * from its {@code pom.xml} follows the same rule — a reactor root owns the lock of every leaf, a
 * lone module its own. {@link #lockFile} places the file beside the owner's manifest: {@code
 * <owner>/jk-lock.toml}, or {@code <owner>/target/jk/shadow/jk-lock.toml} when the owner is
 * shadowed, so the repository stays clean.
 *
 * <p>Modules never own a lockfile. Paths not listed in {@code workspace.modules} are treated as
 * standalone even if nested under a workspace tree.
 */
public final class LockPaths {

    private LockPaths() {}

    /**
     * The project directory that owns the lockfile for {@code projectDir}: the workspace root when
     * inside a workspace (a {@code jk.toml} one or a POM reactor), else {@code projectDir} itself.
     */
    public static Path lockOwnerDir(Path projectDir) {
        Objects.requireNonNull(projectDir, "projectDir");
        Path dir = projectDir.toAbsolutePath().normalize();
        if (ManifestPaths.isShadowed(dir)) {
            // A nested aggregator lists modules too; the outermost reactor owns the lock.
            return PomReactorScan.reactorRootOf(dir).orElse(dir);
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
        Path owner = lockOwnerDir(projectDir);
        Path home = ManifestPaths.isShadowed(owner) ? ManifestPaths.shadowDir(owner) : owner;
        return home.resolve(ManifestPaths.LOCK);
    }

    /**
     * True when locking/building {@code projectDir} is governed by an enclosing (or self)
     * workspace root lock rather than a standalone project lock.
     */
    public static boolean isWorkspaceLock(Path projectDir) {
        Path dir = projectDir.toAbsolutePath().normalize();
        Path owner = lockOwnerDir(dir);
        if (!owner.equals(dir)) {
            return true; // member → root
        }
        return WorkspaceScan.isWorkspaceRoot(owner);
    }
}
