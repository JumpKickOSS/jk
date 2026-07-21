// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoArtifactStoreTest {

    @Test
    void materialize_copies_via_part_then_atomic_move_without_hardlink(@TempDir Path dir) throws IOException {
        Path cache = dir.resolve("cache");
        Path casBlob = dir.resolve("cas/blob");
        Files.createDirectories(casBlob.getParent());
        Files.writeString(casBlob, "artifact-bytes");
        String sha = "a".repeat(64);

        RepoArtifactStore store = new RepoArtifactStore(cache, "central");
        String rel = "com/example/lib/1.0.0/lib-1.0.0.jar";
        store.materialize(rel, casBlob, sha);

        Path artifact = cache.resolve("repos/central").resolve(rel);
        Path sidecar = Path.of(artifact + ".sha256");
        assertThat(artifact).exists();
        assertThat(Files.readString(artifact)).isEqualTo("artifact-bytes");
        assertThat(Files.readString(sidecar)).isEqualTo(sha);
        // No shared inode with the CAS blob (copy, not hardlink).
        assertThat(Files.isSameFile(casBlob, artifact)).isFalse();
        // Crash mid-write leaves at most a .part; successful path leaves none.
        assertThat(Files.exists(artifact.resolveSibling(artifact.getFileName() + ".part")))
                .isFalse();

        // Mutating the repo file must not poison the CAS blob.
        Files.writeString(artifact, "overwritten");
        assertThat(Files.readString(casBlob)).isEqualTo("artifact-bytes");
    }

    @Test
    void materialize_is_idempotent_when_artifact_and_sidecar_exist(@TempDir Path dir) throws IOException {
        Path cache = dir.resolve("cache");
        Path casBlob = dir.resolve("cas/blob");
        Files.createDirectories(casBlob.getParent());
        Files.writeString(casBlob, "v1");
        String sha = "b".repeat(64);

        RepoArtifactStore store = new RepoArtifactStore(cache, "central");
        String rel = "g/a/1/a-1.jar";
        store.materialize(rel, casBlob, sha);
        Path artifact = cache.resolve("repos/central").resolve(rel);
        long mtime = Files.getLastModifiedTime(artifact).toMillis();

        Files.writeString(casBlob, "v2-should-not-replace");
        store.materialize(rel, casBlob, sha);
        assertThat(Files.readString(artifact)).isEqualTo("v1");
        assertThat(Files.getLastModifiedTime(artifact).toMillis()).isEqualTo(mtime);
    }
}
