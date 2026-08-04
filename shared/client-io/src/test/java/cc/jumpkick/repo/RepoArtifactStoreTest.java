// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.util.AtomicWrites;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoArtifactStoreTest {

    @Test
    void materialize_hardlinks_cas_blob_when_filesystem_allows(@TempDir Path dir) throws IOException {
        Path cache = dir.resolve("cache");
        Path casBlob = dir.resolve("cas/blob");
        Files.createDirectories(casBlob.getParent());
        Files.writeString(casBlob, "artifact-bytes");
        String sha = "a".repeat(64);

        // Probe the same NIO API used on Linux/macOS/Windows (CreateHardLinkW on NTFS).
        // On volumes without hard links the implementation falls back to copy — still correct,
        // just without the space win; skip the same-file assertion in that case.
        boolean hardLinks = probeHardLink(dir);

        RepoArtifactStore store = new RepoArtifactStore(cache, "central");
        String rel = "com/example/lib/1.0.0/lib-1.0.0.jar";
        store.materialize(rel, casBlob, sha);

        Path artifact = cache.resolve("repos/central").resolve(rel);
        Path sidecar = Path.of(artifact + ".sha256");
        assertThat(artifact).exists();
        assertThat(Files.readString(artifact)).isEqualTo("artifact-bytes");
        assertThat(Files.readString(sidecar)).isEqualTo(sha);
        if (hardLinks) {
            // Same file identity under the jk-owned store (no double bytes).
            assertThat(Files.isSameFile(casBlob, artifact)).isTrue();
        }
        // Crash mid-write leaves at most a .part; successful path leaves none.
        assertThat(Files.exists(artifact.resolveSibling(artifact.getFileName() + ".part")))
                .isFalse();
    }

    private static boolean probeHardLink(Path dir) throws IOException {
        Path a = dir.resolve(".hl-a");
        Path b = dir.resolve(".hl-b");
        Files.writeString(a, "x");
        try {
            Files.createLink(b, a);
            return true;
        } catch (UnsupportedOperationException | FileSystemException e) {
            return false;
        } finally {
            Files.deleteIfExists(b);
            Files.deleteIfExists(a);
        }
    }

    @Test
    void atomic_replace_of_repo_path_does_not_mutate_cas_blob(@TempDir Path dir) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(probeHardLink(dir), "hard links required");
        Path cache = dir.resolve("cache");
        Path casBlob = dir.resolve("cas/blob");
        Files.createDirectories(casBlob.getParent());
        Files.writeString(casBlob, "artifact-bytes");
        String sha = "a".repeat(64);

        RepoArtifactStore store = new RepoArtifactStore(cache, "central");
        String rel = "com/example/lib/1.0.0/lib-1.0.0.jar";
        store.materialize(rel, casBlob, sha);
        Path artifact = cache.resolve("repos/central").resolve(rel);
        assertThat(Files.isSameFile(casBlob, artifact)).isTrue();

        // Writers under the store must temp + rename (same contract as writeToLocalStore).
        Path tmp = artifact.resolveSibling(artifact.getFileName() + ".part");
        Files.writeString(tmp, "overwritten-via-atomic-replace");
        AtomicWrites.moveInto(tmp, artifact);

        assertThat(Files.readString(casBlob)).isEqualTo("artifact-bytes");
        assertThat(Files.readString(artifact)).isEqualTo("overwritten-via-atomic-replace");
        assertThat(Files.isSameFile(casBlob, artifact)).isFalse();
    }

    @Test
    void materialize_is_idempotent_when_already_hardlinked(@TempDir Path dir) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(probeHardLink(dir), "hard links required");
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

        store.materialize(rel, casBlob, sha);
        assertThat(Files.readString(artifact)).isEqualTo("v1");
        assertThat(Files.isSameFile(casBlob, artifact)).isTrue();
        assertThat(Files.getLastModifiedTime(artifact).toMillis()).isEqualTo(mtime);
    }

    @Test
    void materialize_reclaims_legacy_duplicate_copy_into_hardlink(@TempDir Path dir) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(probeHardLink(dir), "hard links required");
        Path cache = dir.resolve("cache");
        Path casBlob = dir.resolve("cas/blob");
        Files.createDirectories(casBlob.getParent());
        Files.writeString(casBlob, "shared-bytes");
        String sha = "c".repeat(64);

        RepoArtifactStore store = new RepoArtifactStore(cache, "central");
        String rel = "g/a/1/a-1.jar";
        Path artifact = cache.resolve("repos/central").resolve(rel);
        Files.createDirectories(artifact.getParent());
        // Seed the pre-fix layout: full byte copy + sidecar (different file identity).
        Files.copy(casBlob, artifact);
        Files.writeString(Path.of(artifact + ".sha256"), sha);
        assertThat(Files.isSameFile(casBlob, artifact)).isFalse();

        store.materialize(rel, casBlob, sha);
        assertThat(Files.isSameFile(casBlob, artifact)).isTrue();
        assertThat(Files.readString(artifact)).isEqualTo("shared-bytes");
    }

    /** JK-1460: the first-write-wins escape hatch — evict drops both files so the next resolve refetches. */
    @Test
    void evict_removes_the_artifact_and_its_sidecar(@TempDir Path dir) throws IOException {
        Path cache = dir.resolve("cache");
        Path casBlob = dir.resolve("cas/blob");
        Files.createDirectories(casBlob.getParent());
        Files.writeString(casBlob, "artifact-bytes");
        String sha = "b".repeat(64);

        RepoArtifactStore store = new RepoArtifactStore(cache, "central");
        String rel = "com/example/lib/1.0.0/lib-1.0.0.jar";
        store.materialize(rel, casBlob, sha);
        assertThat(store.contains(rel)).isTrue();

        assertThat(store.evict(rel)).isTrue();
        assertThat(store.contains(rel)).isFalse();
        assertThat(cache.resolve("repos/central").resolve(rel)).doesNotExist();
        assertThat(Path.of(cache.resolve("repos/central").resolve(rel) + ".sha256"))
                .doesNotExist();
        // The CAS blob is untouched — evicting a mirror entry must not damage content storage.
        assertThat(casBlob).exists();
        // Evicting again is a no-op, not an error.
        assertThat(store.evict(rel)).isFalse();
    }
}
