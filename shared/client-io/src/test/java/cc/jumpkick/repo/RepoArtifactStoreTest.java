// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoArtifactStoreTest {

    @Test
    void materialize_copies_and_writes_jk_memo(@TempDir Path dir) throws IOException {
        Path cache = dir.resolve("cache");
        Path source = dir.resolve("src.jar");
        Files.writeString(source, "artifact-bytes");
        String sha = Hashing.sha256Hex(source);

        RepoArtifactStore store = new RepoArtifactStore(cache, "central");
        String rel = "com/example/lib/1.0.0/lib-1.0.0.jar";
        store.materialize(rel, source, sha);

        Path artifact = cache.resolve("repos/central").resolve(rel);
        Path memo = ArtifactMemo.jkPath(cache.resolve("repos/central"), rel);
        assertThat(artifact).exists();
        assertThat(Files.readString(artifact)).isEqualTo("artifact-bytes");
        assertThat(memo).exists();
        ArtifactMemo parsed = ArtifactMemo.read(memo).orElseThrow();
        assertThat(parsed.sha256()).isEqualTo(sha);
        assertThat(parsed.coordinate()).isEqualTo("com.example:lib:1.0.0");
        assertThat(Files.isSameFile(source, artifact)).isFalse();
        assertThat(store.verify(rel, sha)).isEqualTo(RepoArtifactStore.IndexState.VERIFIED);
    }

    @Test
    void materialize_is_idempotent_when_already_verified(@TempDir Path dir) throws IOException {
        Path cache = dir.resolve("cache");
        Path source = dir.resolve("src.jar");
        Files.writeString(source, "v1");
        String sha = Hashing.sha256Hex(source);

        RepoArtifactStore store = new RepoArtifactStore(cache, "central");
        String rel = "g/a/1/a-1.jar";
        store.materialize(rel, source, sha);
        Path artifact = cache.resolve("repos/central").resolve(rel);
        long mtime = Files.getLastModifiedTime(artifact).toMillis();

        store.materialize(rel, artifact, sha);
        assertThat(Files.readString(artifact)).isEqualTo("v1");
        assertThat(Files.getLastModifiedTime(artifact).toMillis()).isEqualTo(mtime);
    }

    @Test
    void evict_removes_the_artifact_and_its_memo(@TempDir Path dir) throws IOException {
        Path cache = dir.resolve("cache");
        Path source = dir.resolve("src.jar");
        Files.writeString(source, "artifact-bytes");
        String sha = Hashing.sha256Hex(source);

        RepoArtifactStore store = new RepoArtifactStore(cache, "central");
        String rel = "com/example/lib/1.0.0/lib-1.0.0.jar";
        store.materialize(rel, source, sha);
        assertThat(store.contains(rel)).isTrue();

        assertThat(store.evict(rel)).isTrue();
        assertThat(store.contains(rel)).isFalse();
        assertThat(cache.resolve("repos/central").resolve(rel)).doesNotExist();
        assertThat(ArtifactMemo.jkPath(cache.resolve("repos/central"), rel)).doesNotExist();
        assertThat(source).exists();
        assertThat(store.evict(rel)).isFalse();
    }
}
