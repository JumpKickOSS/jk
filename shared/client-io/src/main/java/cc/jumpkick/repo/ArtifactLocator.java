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
 * Finds a locked artifact as a real {@code *.jar} (or {@code *.aar}) path in the store of the
 * repository the lock row's {@code source} names — keyed by that repository's origin, so two
 * projects that call different origins by one name never read each other's bytes.
 *
 * <p>Every answer is a file the store owns. The Maven local repository, when integration is on,
 * is a read-through source and never an address: a row the store lacks and the local repository
 * holds under the locked digest is copied into the store and answered from there. A build
 * therefore never reads a classpath entry another writer of {@code ~/.m2} — or of the shared
 * test-m2 a forked test JVM runs against — can move or replace under it.
 */
public final class ArtifactLocator {

    private final Path storeRoot;
    private final @Nullable Path m2Root;
    private final boolean m2integration;

    /** {@code m2Root} null, or {@code m2integration} false, means the store alone answers. */
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
     * The row's {@code -sources.jar}: verified against the lock's {@code sources} pin when the row
     * carries one, else wherever a sync or the Maven local repository left it. An editor reads a
     * sources jar and a build never does, so an unpinned one is answered without a digest.
     */
    public Optional<Path> locateSources(Lockfile.Artifact pkg) {
        if (pkg.checksum() == null || pkg.name().indexOf(':') < 0) return Optional.empty();
        Coordinate sources = new Coordinate(pkg.moduleGroup(), pkg.moduleArtifact(), pkg.version(), "sources", "jar");
        String rel = MavenLayout.artifactPath(sources);
        String hex = pkg.sourcesChecksumHex();
        if (hex != null) return locate(pkg.source(), rel, hex, sources.toGav());
        if (!RepoArtifactResolver.isNamedRemote(RepoArtifactResolver.repoName(pkg.source()))) return Optional.empty();
        Optional<Path> found =
                RepoArtifactStore.forSource(storeRoot, pkg.source()).locate(rel);
        if (found.isPresent()) return found.map(p -> p.toAbsolutePath().normalize());
        Path m2 = m2Root;
        Path m2File = m2integration && m2 != null ? MavenLayout.safeResolve(m2, rel) : null;
        return m2File != null && Files.isRegularFile(m2File)
                ? Optional.of(m2File.toAbsolutePath().normalize())
                : Optional.empty();
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
        // The lock row names the path; ~/.m2 is a root the row must not climb out of.
        Path m2File = m2integration && m2 != null && !storeOnly ? MavenLayout.safeResolve(m2, relativePath) : null;
        Optional<Path> found = fromStore(store, relativePath, expectedSha256);
        if (found.isPresent()) return found;
        if (m2File != null
                && Files.isRegularFile(m2File)
                && verified(m2File, m2MemoPath(store, relativePath), gav, expectedSha256)) {
            store.materialize(relativePath, m2File, expectedSha256);
            return fromStore(store, relativePath, expectedSha256);
        }
        return Optional.empty();
    }

    /** The row in {@code store}, else on the first-party shelf, where every first-party install lands. */
    private Optional<Path> fromStore(RepoArtifactStore store, String relativePath, String expectedSha256) {
        Optional<Path> found = store.locate(relativePath, expectedSha256);
        if (found.isPresent()) return found.map(p -> p.toAbsolutePath().normalize());
        Path storeDir = store.root();
        if (storeDir == null || !RepoArtifactResolver.JK_LOCAL.equals(String.valueOf(storeDir.getFileName()))) {
            found = RepoArtifactStore.forStoreId(storeRoot, RepoArtifactResolver.JK_LOCAL)
                    .locate(relativePath, expectedSha256);
        }
        return found.map(p -> p.toAbsolutePath().normalize());
    }

    /**
     * The ~/.m2 probe gets its own memo ({@code <artifact>.m2.jk}), distinct from the store's own
     * {@code .jk} sidecar: one memo records one blob's (mtime, size), and the m2 file and the store
     * file are two blobs. It lives beside the repository's own store, so two origins sharing a name
     * keep separate m2 verdicts too.
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
