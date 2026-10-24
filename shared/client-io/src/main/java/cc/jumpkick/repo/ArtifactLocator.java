// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Coordinate;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Finds a locked artifact as a real {@code *.jar} (or {@code *.aar}) path in the store of the
 * repository the lock row's {@code source} names — keyed by that repository's origin, so two
 * projects that call different origins by one name never read each other's bytes.
 *
 * <p>Where the artifact is depends on the repository ({@link M2Backing}): the Maven local
 * repository for Central and the repositories {@code settings.xml} declares, the store's own tree
 * for the rest. Either way the answer is a file whose digest matches the lock row, checked through
 * the memo jk keeps in the store, so another writer of {@code ~/.m2} replacing a jar changes the
 * answer from a path to a miss, never to different bytes.
 */
public final class ArtifactLocator {

    private final Path storeRoot;
    private final boolean m2integration;

    /** {@code m2integration} false means every repository's artifacts live in its own store tree. */
    public ArtifactLocator(Path storeRoot, boolean m2integration) {
        this.storeRoot = Objects.requireNonNull(storeRoot, "storeRoot");
        this.m2integration = m2integration;
    }

    /** Store-only locator: no repository's artifacts are read from the Maven local repository. */
    public ArtifactLocator(Path storeRoot) {
        this(storeRoot, false);
    }

    public Optional<Path> locate(Lockfile.Artifact pkg) {
        if (pkg == null || pkg.checksumHex() == null) return Optional.empty();
        if (pkg.name().indexOf(':') < 0) return Optional.empty();
        Coordinate coord = pkg.coordinate();
        String rel = MavenLayout.artifactPath(coord);
        return locate(pkg.source(), rel, pkg.checksumHex());
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
        if (hex != null) return locate(pkg.source(), rel, hex);
        if (!RepoArtifactResolver.isNamedRemote(RepoArtifactResolver.repoName(pkg.source()))) return Optional.empty();
        return RepoArtifactStore.forSource(storeRoot, pkg.source(), m2integration)
                .locate(rel)
                .map(p -> p.toAbsolutePath().normalize());
    }

    /**
     * @param source the lock row's {@code "<name>+<url>"} source; a bare {@code jk-local} or a
     *     synthetic source reads the first-party shelf only
     */
    public Optional<Path> locate(
            @Nullable String source, @Nullable String relativePath, @Nullable String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.isBlank() || relativePath == null) return Optional.empty();
        String repoName = source == null ? null : RepoArtifactResolver.repoName(source);
        boolean storeOnly = !RepoArtifactResolver.isNamedRemote(repoName);
        RepoArtifactStore store = storeOnly || source == null
                ? RepoArtifactStore.forStoreId(storeRoot, RepoArtifactResolver.JK_LOCAL)
                : RepoArtifactStore.forSource(storeRoot, source, m2integration);
        return fromStore(store, relativePath, expectedSha256);
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
}
