// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Coordinate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The process-wide artifact memo against a repository that republishes a GAV: the pinned
 * re-fetch that evicts the stale copy must leave the memo describing the bytes now on disk.
 */
class RepoGroupTest {

    @BeforeEach
    void clear() {
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
    }

    @Test
    void a_pinned_refetch_refreshes_the_memo_the_next_unpinned_fetch_reads(@TempDir Path tmp) throws Exception {
        Coordinate coord = Coordinate.of("com.example", "lib", "1.0");
        Path repoDir = tmp.resolve("repo");
        Path jar = repoDir.resolve(MavenLayout.artifactPath(coord));
        Files.createDirectories(jar.getParent());
        byte[] first = "first-publication".getBytes(StandardCharsets.UTF_8);
        byte[] second = "republished-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(jar, first);

        Cas cas = new Cas(tmp.resolve("cas"));
        RepoGroup group = new RepoGroup(
                List.of(new MavenRepo("internal", repoDir.toUri(), new Http(), cas, RepoCredential.ANONYMOUS, false)));

        Optional<RepoGroup.RepoFetched> warm = group.tryFetchArtifact(coord);
        assertThat(warm).isPresent();
        assertThat(warm.get().fetched().sha256()).isEqualTo(Hashing.sha256Hex(first));

        // The repository republishes the same GAV and the lock is re-pinned to the new bytes.
        Files.write(jar, second);
        String pin = Hashing.sha256Hex(second);
        Optional<RepoGroup.RepoFetched> pinned = group.tryFetchArtifact(coord, pin);
        assertThat(pinned).isPresent();
        assertThat(pinned.get().fetched().sha256()).isEqualTo(pin);

        // What the next lock in this engine records: the digest of the bytes on disk, not the
        // memo from before the re-fetch.
        Optional<RepoGroup.RepoFetched> after = group.tryFetchArtifact(coord);
        assertThat(after).isPresent();
        assertThat(after.get().fetched().sha256()).isEqualTo(pin);
        assertThat(Hashing.sha256Hex(after.get().fetched().cachePath())).isEqualTo(pin);
    }

    @Test
    void a_pinned_fetch_that_matches_the_memo_is_served_from_it(@TempDir Path tmp) throws Exception {
        Coordinate coord = Coordinate.of("com.example", "lib", "1.0");
        Path repoDir = tmp.resolve("repo");
        Path jar = repoDir.resolve(MavenLayout.artifactPath(coord));
        Files.createDirectories(jar.getParent());
        byte[] bytes = "steady-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(jar, bytes);
        RepoGroup group = new RepoGroup(List.of(new MavenRepo(
                "internal",
                repoDir.toUri(),
                new Http(),
                new Cas(tmp.resolve("cas")),
                RepoCredential.ANONYMOUS,
                false)));

        Optional<RepoGroup.RepoFetched> warm = group.tryFetchArtifact(coord);
        Optional<RepoGroup.RepoFetched> pinned = group.tryFetchArtifact(coord, Hashing.sha256Hex(bytes));
        assertThat(pinned).isPresent();
        assertThat(pinned.get()).isSameAs(warm.orElseThrow());
    }
}
