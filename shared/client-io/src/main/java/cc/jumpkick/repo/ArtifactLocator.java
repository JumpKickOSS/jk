// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Coordinate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Finds a locked artifact as a real {@code *.jar} (or {@code *.aar}) path: Maven local repository
 * first when integration is on and the digest matches, else the store of the repository the lock
 * row's {@code source} names — keyed by that repository's origin, so two projects that call
 * different origins by one name never read each other's bytes.
 */
public final class ArtifactLocator {

    private final Path storeRoot;
    private final @Nullable Path m2Root;
    private final boolean m2integration;

    public ArtifactLocator(Path storeRoot, @Nullable Path m2Root, boolean m2integration) {
        this.storeRoot = Objects.requireNonNull(storeRoot, "storeRoot");
        this.m2Root = m2Root;
        this.m2integration = m2integration && m2Root != null;
    }

    /** Store-only locator (no Maven local repo). */
    public ArtifactLocator(Path storeRoot) {
        this(storeRoot, null, false);
    }

    public Optional<Path> locate(Lockfile.Artifact pkg) {
        if (pkg == null || pkg.checksumHex() == null) return Optional.empty();
        if (pkg.name().indexOf(':') < 0) return Optional.empty();
        Coordinate coord = pkg.coordinate();
        String rel = MavenLayout.artifactPath(coord);
        return locate(pkg.source(), rel, pkg.checksumHex(), coord.toGav());
    }

    /**
     * @param source the lock row's {@code "<name>+<url>"} source; a bare {@code jk-local} or a
     *     synthetic source reads the first-party shelf only
     */
    public Optional<Path> locate(
            @Nullable String source, @Nullable String relativePath, @Nullable String expectedSha256, String gav) {
        if (expectedSha256 == null || expectedSha256.isBlank() || relativePath == null) return Optional.empty();
        String repoName = source == null ? null : RepoArtifactResolver.repoName(source);
        boolean storeOnly = !RepoArtifactResolver.isNamedRemote(repoName);
        RepoArtifactStore store = storeOnly || source == null
                ? RepoArtifactStore.forStoreId(storeRoot, RepoArtifactResolver.JK_LOCAL)
                : RepoArtifactStore.forSource(storeRoot, source);
        Path m2 = m2Root;
        if (m2integration && m2 != null && !storeOnly) {
            // The lock row names the path; ~/.m2 is a root the row must not climb out of.
            Path m2File = MavenLayout.safeResolve(m2, relativePath);
            if (Files.isRegularFile(m2File) && verified(m2File, m2MemoPath(store, relativePath), gav, expectedSha256)) {
                return Optional.of(m2File.toAbsolutePath().normalize());
            }
        }
        Optional<Path> found = store.locate(relativePath, expectedSha256);
        if (found.isPresent()) return found.map(p -> p.toAbsolutePath().normalize());
        // First-party installs always land in jk-local.
        Path storeDir = store.root();
        if (storeDir == null || !RepoArtifactResolver.JK_LOCAL.equals(String.valueOf(storeDir.getFileName()))) {
            found = RepoArtifactStore.forStoreId(storeRoot, RepoArtifactResolver.JK_LOCAL)
                    .locate(relativePath, expectedSha256);
        }
        return found.map(p -> p.toAbsolutePath().normalize());
    }

    /**
     * The ~/.m2 probe gets its OWN memo ({@code <artifact>.m2.jk}), distinct from the store's own
     * {@code .jk} sidecar. One shared memo can only record one blob's (mtime,size), so the m2 file
     * and the store file kept invalidating each other's fast path and re-hashing the full jar on
     * every resolve when they diverged (a stale ~/.m2 after a re-lock). It lives beside the
     * repository's own store, so two origins sharing a name keep separate m2 verdicts too.
     */
    private Path m2MemoPath(RepoArtifactStore store, String relativePath) {
        Path dir = Objects.requireNonNull(store.root(), "a store with a root");
        Path memo = ArtifactMemo.jkPath(dir, relativePath);
        String n = memo.getFileName().toString();
        String m2n = (n.endsWith(".jk") ? n.substring(0, n.length() - 3) : n) + ".m2.jk";
        return memo.resolveSibling(m2n);
    }

    private static boolean verified(Path blob, Path jkFile, String gav, String expectedSha256) {
        try {
            return ArtifactMemo.verify(blob, jkFile, gav, expectedSha256);
        } catch (IOException e) {
            return false;
        }
    }
}
