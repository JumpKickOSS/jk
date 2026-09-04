// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Whether {@code jk-lock.toml} still matches the manifests it was derived from.
 *
 * <p>Staleness is content-addressed via {@code manifests-sha256}: a live digest of every
 * {@code jk.toml} that feeds the lock is compared to the stamp in the lockfile. Survives fresh
 * git clones (equalized mtimes). Locks <em>without</em> a valid digest are always stale — re-lock
 * once to stamp one; there is no mtime fallback.
 *
 * <p>A clean digest is necessary but not sufficient: a lock can also be missing a <em>fact</em>
 * jk writes for the current manifests. Today that is the {@code [native] metadata-repository}
 * pin — a native-image project whose lock predates the pin digests clean forever, so
 * {@link #isStale} also rules stale when {@link LockNativePin} selects a repository the lock
 * never stamped.
 */
public final class LockFreshness {

    private LockFreshness() {}

    /**
     * True when {@code lockFile} is missing, or when the lock no longer reflects manifests under
     * its owner directory (workspace root or standalone project).
     */
    public static boolean needsRefresh(Path projectDir) {
        Path owner = LockPaths.lockOwnerDir(projectDir);
        Path lockFile = owner.resolve(ManifestPaths.LOCK);
        if (!Files.isRegularFile(lockFile)) return true;
        return workspaceLockStale(owner, lockFile);
    }

    /**
     * True when {@code rootLock} is absent or no longer matches the root manifest or any declared
     * workspace member manifest. One digest comparison covers everything: the digest is
     * workspace-wide (owner = the lock's directory) and already folds member and path-dep
     * manifests in, so a per-module loop would recompute the identical digest N times.
     */
    public static boolean workspaceLockStale(Path root, Path rootLock) {
        if (!Files.exists(rootLock)) return true;
        return isStale(root, rootLock);
    }

    /**
     * Staleness of the lock against live manifests.
     *
     * <ul>
     *   <li>Valid {@code manifests-sha256} present: stale when the live digest of the lock owner
     *       differs.
     *   <li>Missing, blank, or invalid digest: <strong>always stale</strong> (force re-lock to stamp).
     *   <li>Digest clean but the manifests build a native image and the lock carries no
     *       {@code [native]} pin: stale (re-lock to stamp the pin).
     * </ul>
     *
     * <p>Both the project {@code jk.toml} and the lock must exist for a non-stale answer; missing
     * lock is handled by {@link #needsRefresh}. Unreadable lock or digest recompute failure → stale
     * (fail closed).
     */
    public static boolean isStale(Path dir, Path lockFile) {
        try {
            Path buildFile = dir.resolve(ManifestPaths.MANIFEST);
            if (!Files.exists(buildFile) || !Files.exists(lockFile)) return false;

            Lockfile lock = LockfileReader.read(lockFile);
            String stored = lock.manifestsSha256();
            if (stored == null || !isValidDigest(stored)) {
                return true; // no trustworthy stamp → re-lock
            }
            Path owner = lockFile.toAbsolutePath().normalize().getParent();
            if (owner == null) return true; // a lock at a filesystem root stamps nothing
            String live = LockManifestDigest.compute(owner);
            if (!stored.equalsIgnoreCase(live)) return true;
            return missingNativePin(owner, lock);
        } catch (Exception e) {
            return true; // unreadable lock / digest failure → re-lock
        }
    }

    /**
     * True when the manifests under {@code owner} build a native image but {@code lock} carries no
     * {@code [native] metadata-repository} pin. Conflicting member selectors are also stale — fail
     * closed; the re-lock surfaces {@link LockNativePin}'s conflict message.
     */
    private static boolean missingNativePin(Path owner, Lockfile lock) throws IOException {
        if (lock.nativeMetadata() != null) return false;
        try {
            return LockNativePin.selector(owner).isPresent();
        } catch (IllegalStateException conflictingSelectors) {
            return true;
        }
    }

    /** {@code true} when {@code hex} is a 64-char hex SHA-256 (case-insensitive). */
    static boolean isValidDigest(@Nullable String hex) {
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
