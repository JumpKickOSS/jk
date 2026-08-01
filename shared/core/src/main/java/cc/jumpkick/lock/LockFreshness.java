// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether {@code jk-lock.toml} still matches the manifests it was derived from.
 *
 * <p>Staleness is content-addressed via {@code manifests-sha256}: a live digest of every
 * {@code jk.toml} that feeds the lock is compared to the stamp in the lockfile. Survives fresh
 * git clones (equalized mtimes). Locks <em>without</em> a valid digest are always stale — re-lock
 * once to stamp one; there is no mtime fallback.
 */
public final class LockFreshness {

    private LockFreshness() {}

    /**
     * True when {@code lockFile} is missing, or when the lock no longer reflects manifests under
     * its owner directory (workspace root or standalone project).
     */
    public static boolean needsRefresh(Path projectDir) {
        Path owner = LockPaths.lockOwnerDir(projectDir);
        Path lockFile = owner.resolve(LockPaths.FILE_NAME);
        if (!Files.isRegularFile(lockFile)) return true;
        return workspaceLockStale(owner, lockFile);
    }

    /**
     * True when {@code rootLock} is absent or no longer matches the root manifest or any declared
     * workspace member manifest.
     */
    public static boolean workspaceLockStale(Path root, Path rootLock) {
        if (!Files.exists(rootLock)) return true;
        // Digest is workspace-wide; one comparison is enough when the stamp is present.
        if (isStale(root, rootLock)) return true;
        try {
            Path toml = root.resolve("jk.toml");
            if (!Files.isRegularFile(toml)) return false;
            JkBuild rootBuild = JkBuildParser.parseLocal(toml);
            if (rootBuild.workspace() != null) {
                for (String module : rootBuild.workspace().modules()) {
                    Path moduleDir = root.resolve(module).normalize();
                    if (isStale(moduleDir, rootLock)) return true;
                }
            }
        } catch (Exception e) {
            return true; // unreadable → assume stale
        }
        return false;
    }

    /**
     * Staleness of the lock against live manifests.
     *
     * <ul>
     *   <li>Valid {@code manifests-sha256} present: stale iff live digest of the lock owner differs.
     *   <li>Missing, blank, or invalid digest: <strong>always stale</strong> (force re-lock to stamp).
     * </ul>
     *
     * <p>Both the project {@code jk.toml} and the lock must exist for a non-stale answer; missing
     * lock is handled by {@link #needsRefresh}. Unreadable lock or digest recompute failure → stale
     * (fail closed).
     */
    public static boolean isStale(Path dir, Path lockFile) {
        try {
            Path buildFile = dir.resolve("jk.toml");
            if (!Files.exists(buildFile) || !Files.exists(lockFile)) return false;

            Lockfile lock = LockfileReader.read(lockFile);
            String stored = lock.manifestsSha256();
            if (!isValidDigest(stored)) {
                return true; // no trustworthy stamp → re-lock
            }
            Path owner = lockFile.toAbsolutePath().normalize().getParent();
            String live = LockManifestDigest.compute(owner);
            return !stored.equalsIgnoreCase(live);
        } catch (Exception e) {
            return true; // unreadable lock / digest failure → re-lock
        }
    }

    /** {@code true} when {@code hex} is a 64-char hex SHA-256 (case-insensitive). */
    static boolean isValidDigest(String hex) {
        if (hex == null || hex.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = hex.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean lower = c >= 'a' && c <= 'f';
            boolean upper = c >= 'A' && c <= 'F';
            if (!digit && !lower && !upper) return false;
        }
        return true;
    }
}
