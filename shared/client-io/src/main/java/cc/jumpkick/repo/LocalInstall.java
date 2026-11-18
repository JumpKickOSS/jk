// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.RepositorySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * This machine's installs as a lock row's source: the Maven local repository and jk's {@code
 * repos/jk-local} shelf, the two places {@code jk install} writes. A row one of them served says
 * {@code source = "local-install"} and no directory, since each machine keeps its installs where
 * it keeps them; it is read from whichever of them holds the bytes the row pins.
 *
 * <p>An install changes under a lock: the other project is rebuilt and installed again, or the
 * machine never had it. A lock whose such row no longer has its bytes here is stale, as one
 * {@code jk.toml} moved past is ({@link #staleReason}), so the build re-locks and reads the
 * install again, and {@code jk explain} says the same before it runs.
 */
public final class LocalInstall {

    /** The bare lockfile source of a row an install served. */
    public static final String SOURCE = RepositorySpec.LOCAL_INSTALL;

    private LocalInstall() {}

    /** True when {@code source} is {@link #SOURCE}. */
    public static boolean isSource(@Nullable String source) {
        return SOURCE.equals(source);
    }

    /**
     * {@code relativePath} with the digest {@code sha256} in one of this machine's installs: the
     * Maven local repository, then the shelf under {@code storeRoot}.
     */
    public static Optional<Path> locate(Path storeRoot, String relativePath, String sha256) {
        Path m2 = M2Dirs.localRepository().toAbsolutePath().normalize();
        // Read as the Maven local repository it is (not under SOURCE, which names the resolver's copy).
        Optional<Path> found = RepoArtifactStore.forRepository(storeRoot, "maven-local", m2.toUri())
                .locate(relativePath, sha256);
        if (found.isEmpty()) {
            found = RepoArtifactStore.forStoreId(storeRoot, RepositorySpec.JK_LOCAL)
                    .locate(relativePath, sha256);
        }
        return found.map(p -> p.toAbsolutePath().normalize());
    }

    /**
     * The first row of {@code lock} an install served whose pinned bytes no install here holds:
     * the other project installed again since the lock, or never on this machine. Null when none.
     */
    public static Lockfile.@Nullable Artifact moved(Lockfile lock, Path storeRoot) {
        for (Lockfile.Artifact row : lock.artifacts()) {
            if (!isSource(row.source())
                    || row.checksumHex() == null
                    || row.name().indexOf(':') < 0) continue;
            String hex = row.checksumHex();
            if (hex == null) continue;
            if (locate(storeRoot, MavenLayout.artifactPath(row.coordinate()), hex)
                    .isEmpty()) return row;
        }
        return null;
    }

    /**
     * Why the lock at {@code lockFile} is stale on account of an install, or null: the sentence
     * {@link LockFreshness#STALE} is for a moved manifest, naming the coordinate whose install
     * changed or is missing. A lock that names no install costs a read of the lock alone.
     */
    public static @Nullable String staleReason(Path lockFile, Path storeRoot) {
        if (!Files.isRegularFile(lockFile)) return null;
        Lockfile lock;
        try {
            lock = LockfileReader.read(lockFile);
        } catch (IOException | RuntimeException unreadable) {
            return null; // the manifest check reads the same file and calls it stale
        }
        Lockfile.Artifact row = moved(lock, storeRoot);
        if (row == null) return null;
        return "jk-lock.toml is stale: " + row.coordinate().toGav()
                + " came from this machine's installs (jk install), which no longer hold the bytes it pins";
    }

    /**
     * Why the lock that governs {@code dir} is stale, or null: its manifests moved ({@link
     * LockFreshness#STALE}), or an install a row came from changed ({@link #staleReason}). Every
     * surface that asks whether a build re-locks asks this, so the forecast and the build agree.
     */
    public static @Nullable String lockStaleReason(Path dir, Path lockFile, Path storeRoot) {
        if (LockFreshness.isStale(dir, lockFile)) return LockFreshness.STALE;
        return staleReason(lockFile, storeRoot);
    }

    /** {@link LockFreshness#needsRefresh} or an install a row came from changed. */
    public static boolean needsRefresh(Path projectDir, Path storeRoot) {
        if (LockFreshness.needsRefresh(projectDir)) return true;
        Path lockFile = LockPaths.lockFile(LockPaths.lockOwnerDir(projectDir));
        return staleReason(lockFile, storeRoot) != null;
    }
}
