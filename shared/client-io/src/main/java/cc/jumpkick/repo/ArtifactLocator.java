// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Coordinate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Finds a locked artifact as a real {@code *.jar} (or {@code *.aar}) path: Maven local repository
 * first when integration is on and the digest matches, else {@code JK_STORE_DIR/repos/<name>/}.
 */
public final class ArtifactLocator {

    private final Path storeRoot;
    private final Path m2Root;
    private final boolean m2integration;

    public ArtifactLocator(Path storeRoot, Path m2Root, boolean m2integration) {
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
        String repoName = RepoArtifactResolver.repoName(pkg.source());
        if (pkg.name().indexOf(':') < 0) return Optional.empty();
        Coordinate coord = pkg.coordinate();
        String rel = MavenLayout.artifactPath(coord);
        return locate(repoName, rel, pkg.checksumHex(), coord.toGav());
    }

    public Optional<Path> locate(String repoName, String relativePath, String expectedSha256, String gav) {
        if (expectedSha256 == null || expectedSha256.isBlank() || relativePath == null) return Optional.empty();
        boolean storeOnly = !RepoArtifactResolver.isNamedRemote(repoName);
        if (m2integration && !storeOnly) {
            Path m2File = m2Root.resolve(relativePath);
            if (Files.isRegularFile(m2File)
                    && verified(m2File, memoPath(repoName, relativePath), gav, expectedSha256)) {
                return Optional.of(m2File.toAbsolutePath().normalize());
            }
        }
        String name = storeOnly || repoName == null || repoName.isBlank() ? "local" : repoName;
        RepoArtifactStore store = RepoArtifactStore.forRepoName(storeRoot, name);
        Optional<Path> found = store.locate(relativePath, expectedSha256);
        if (found.isPresent()) return found.map(p -> p.toAbsolutePath().normalize());
        if (!"local".equals(name)) {
            found = RepoArtifactStore.forRepoName(storeRoot, "local").locate(relativePath, expectedSha256);
        }
        return found.map(p -> p.toAbsolutePath().normalize());
    }

    private Path memoPath(String repoName, String relativePath) {
        String name = repoName == null || repoName.isBlank() ? "local" : repoName;
        return ArtifactMemo.jkPath(storeRoot.resolve("repos").resolve(name), relativePath);
    }

    private static boolean verified(Path blob, Path jkFile, String gav, String expectedSha256) {
        try {
            return ArtifactMemo.verify(blob, jkFile, gav, expectedSha256);
        } catch (IOException e) {
            return false;
        }
    }
}
