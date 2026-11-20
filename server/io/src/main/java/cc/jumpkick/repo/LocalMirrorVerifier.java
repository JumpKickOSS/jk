// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.repo.MavenRepo.ArtifactNotFoundException;
import cc.jumpkick.repo.MavenRepo.Fetched;
import cc.jumpkick.task.RunNotices;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What one repository's store copy of a file answers without network I/O: a copy a pin or a
 * {@code .jk} memo vouches for, or an unvouched candidate from the Maven local repository that only
 * the repository's published checksum can vouch for ({@link MavenRepo} asks it).
 */
final class LocalMirrorVerifier {

    private final RepoArtifactStore store;
    private final URI baseUrl;
    private final String name;

    /** @param baseUrl the repository's normalized URL, which a returned copy answers for */
    LocalMirrorVerifier(RepoArtifactStore store, URI baseUrl, String name) {
        this.store = store;
        this.baseUrl = baseUrl;
        this.name = name;
    }

    /**
     * True for a file in the Maven local repository that neither a pin nor a {@code .jk} memo vouches
     * for. It is only a candidate: the repository's published checksum is the authority, and with
     * none published, or none readable (offline, unreachable), nothing vouches for it.
     */
    boolean unvouched(String relativePath, @Nullable String expectedSha256) {
        return expectedSha256 == null
                && store.inM2(relativePath)
                && store.readSha256Sidecar(relativePath).isEmpty()
                && store.locate(relativePath).isPresent();
    }

    /** The store's copy of {@code relativePath} with its digest, read and hashed but not recorded. */
    Optional<Fetched> candidate(String relativePath) {
        Optional<Path> located = store.locate(relativePath);
        String sha = located.map(LocalMirrorVerifier::hashQuietly).orElse("");
        if (located.isEmpty() || sha.isBlank()) return Optional.empty();
        try {
            return Optional.of(
                    new Fetched(baseUrl.resolve(relativePath), located.get(), sha, Files.size(located.get())));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * The store copy of {@code relativePath} once its bytes are known: under a pin, a copy that
     * verifies as the pin; without one, a copy that is still what its {@code .jk} memo recorded. A
     * memo that still describes the file (size and mtime) answers in one stat; otherwise the file is
     * re-hashed and the memo refreshed. A copy that verifies as neither — a torn or truncated file
     * beside an intact memo, a copy whose pin moved — is evicted so the network leg replaces it.
     * Empty on a miss, after an eviction, and for an {@linkplain #unvouched unvouched} file, which is
     * neither hashed nor given a memo here.
     */
    Optional<Fetched> stored(String relativePath, @Nullable String expectedSha256) {
        if (unvouched(relativePath, expectedSha256)) return Optional.empty();
        Optional<Path> located = store.locate(relativePath);
        if (located.isEmpty()) return Optional.empty();
        Path path = located.get();
        try {
            String sha = expectedSha256 != null
                    ? expectedSha256
                    : store.readSha256Sidecar(relativePath).orElseGet(() -> hashQuietly(path));
            if (sha.isBlank() || store.verify(relativePath, sha) != RepoArtifactStore.IndexState.VERIFIED) {
                store.evict(relativePath);
                return Optional.empty();
            }
            URI uri = baseUrl.resolve(relativePath);
            return Optional.of(new Fetched(uri, path, sha.toLowerCase(Locale.ROOT), Files.size(path)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * The store copy a flight that ended after this caller looked has placed, read again by the
     * caller that leads the next flight for it. A copy only counts with a pin or a {@code .jk}
     * memo, which a verified download writes as it places the file: a Maven-local file with
     * neither is the outer look's to confirm, never this one's to adopt.
     */
    Optional<Fetched> placedSinceMiss(String relativePath, @Nullable String expectedSha256) {
        if (expectedSha256 == null && store.readSha256Sidecar(relativePath).isEmpty()) return Optional.empty();
        return stored(relativePath, expectedSha256);
    }

    /**
     * An offline fetch, served from the store: a coordinate that was never fetched (or mirrored)
     * here, or whose only copy is an {@linkplain #unvouched unvouched} file in the Maven local
     * repository, is treated as not-found and the resolver falls through cleanly.
     */
    Fetched offline(Coordinate coord, String relativePath, @Nullable String expectedSha256) throws IOException {
        if (unvouched(relativePath, expectedSha256)) {
            noteUnverifiedOffline(coord);
            throw new ArtifactNotFoundException(unverifiedOffline(coord), coord);
        }
        Optional<Fetched> local = stored(relativePath, expectedSha256);
        if (local.isPresent()) return local.get();
        throw new ArtifactNotFoundException(
                "offline: " + coord + " (" + relativePath + ") not in local index for " + name);
    }

    /** Said once per run: an offline lock found files in {@code ~/.m2} it cannot use. */
    static void noteUnverifiedOffline(Coordinate coord) {
        RunNotices.warnOnce("m2-unverified-offline", () -> "jk: warning: " + unverifiedOffline(coord));
    }

    private static String unverifiedOffline(Coordinate coord) {
        return "offline: " + coord + " in ~/.m2 is unverified; run jk lock online first";
    }

    /** {@code path}'s SHA-256 for a store copy that has no memo to speak for it; blank when unreadable. */
    private static String hashQuietly(Path path) {
        try {
            return Hashing.sha256Hex(path);
        } catch (IOException e) {
            return "";
        }
    }
}
