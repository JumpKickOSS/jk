// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.util.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
    void legacy_local_store_is_folded_into_jk_local(@TempDir Path dir) throws IOException {
        RepoArtifactStore.clearLegacyMigrationMemoForTest();
        Path cache = dir.resolve("cache");
        Path legacy = cache.resolve("repos/local/com/example/app/1.0/app-1.0.jar");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "pre-rename-bytes");

        new RepoArtifactStore(cache, "central"); // any store construction migrates

        assertThat(cache.resolve("repos/local")).doesNotExist();
        assertThat(cache.resolve("repos/jk-local/com/example/app/1.0/app-1.0.jar"))
                .exists()
                .content()
                .isEqualTo("pre-rename-bytes");
        assertThat(RepoArtifactStore.legacyLocalPending(cache)).isFalse();
    }

    @Test
    void legacy_merge_keeps_the_jk_local_copy_on_collision(@TempDir Path dir) throws IOException {
        RepoArtifactStore.clearLegacyMigrationMemoForTest();
        Path cache = dir.resolve("cache");
        Path legacyDup = cache.resolve("repos/local/g/a/1/a-1.jar");
        Path legacyOnly = cache.resolve("repos/local/g/b/1/b-1.jar");
        Path kept = cache.resolve("repos/jk-local/g/a/1/a-1.jar");
        Files.createDirectories(legacyDup.getParent());
        Files.createDirectories(legacyOnly.getParent());
        Files.createDirectories(kept.getParent());
        Files.writeString(legacyDup, "old-copy");
        Files.writeString(legacyOnly, "only-in-legacy");
        Files.writeString(kept, "new-copy");

        RepoArtifactStore.migrateLegacyLocal(cache);

        assertThat(kept).content().isEqualTo("new-copy");
        assertThat(cache.resolve("repos/jk-local/g/b/1/b-1.jar")).content().isEqualTo("only-in-legacy");
        assertThat(cache.resolve("repos/local")).doesNotExist();
    }

    @Test
    void user_remote_named_local_after_the_rename_is_never_migrated(@TempDir Path dir) throws IOException {
        RepoArtifactStore.clearLegacyMigrationMemoForTest();
        Path cache = dir.resolve("cache");
        // First contact with a clean store stamps the rename marker...
        new RepoArtifactStore(cache, "central");
        // ...then a user remote actually named "local" fills its own mirror.
        Path mirror = cache.resolve("repos/local/g/a/1/a-1.jar");
        Files.createDirectories(mirror.getParent());
        Files.writeString(mirror, "user-remote-bytes");

        // A fresh process constructs stores again: the marker keeps the mirror in place.
        RepoArtifactStore.clearLegacyMigrationMemoForTest();
        new RepoArtifactStore(cache, "local");

        assertThat(mirror).content().isEqualTo("user-remote-bytes");
        assertThat(cache.resolve("repos/jk-local/g/a/1/a-1.jar")).doesNotExist();
        assertThat(RepoArtifactStore.legacyLocalPending(cache)).isFalse();
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

    @Test
    void evict_repos_down_to_budget_drops_coldest_and_spares_jk_local(@TempDir Path dir) throws IOException {
        // The repos/ tree must be size-bounded — evict coldest third-party jars first,
        // keep repos/jk-local (first-party, no re-fetch source).
        Path cache = dir.resolve("cache");
        RepoArtifactStore central = new RepoArtifactStore(cache, "central");
        Path a = mkjar(dir, "a", 10_000);
        Path b = mkjar(dir, "b", 10_000);
        central.materialize("g/a/1/a-1.jar", a, Hashing.sha256Hex(a));
        central.materialize("g/b/1/b-1.jar", b, Hashing.sha256Hex(b));
        // First-party under repos/jk-local must never be evicted.
        RepoArtifactStore.writeToLocalStore(cache, "g/local/1/local-1.jar", mkjar(dir, "local", 10_000));

        // a is colder than b.
        Map<String, Long> atimes = Map.of(Hashing.sha256Hex(a), 1_000L, Hashing.sha256Hex(b), 9_000L);

        // Budget fits one 10k jar → the coldest third-party (a) is evicted, b kept.
        var report = RepoArtifactStore.evictReposDownTo(cache, 12_000, atimes, false);
        assertThat(report.deleted()).isEqualTo(1);
        assertThat(central.contains("g/a/1/a-1.jar")).isFalse();
        assertThat(central.contains("g/b/1/b-1.jar")).isTrue();
        assertThat(cache.resolve("repos/jk-local/g/local/1/local-1.jar")).exists();
    }

    private static Path mkjar(Path dir, String name, int size) throws IOException {
        Path f = dir.resolve(name + ".jar");
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[i] = (byte) (name.charAt(0) + i);
        Files.write(f, bytes);
        return f;
    }

    @Test
    void rejects_a_repo_name_that_escapes_the_store(@TempDir Path dir) {
        // A repo name is a raw config/lockfile substring; it must not break out of repos/.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RepoArtifactStore(dir, "../../evil"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RepoArtifactStore(dir, ".."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_a_relative_path_that_escapes_the_store(@TempDir Path dir) throws IOException {
        // A hostile GAV must not write outside repos/<name>/ (arbitrary file write).
        Path source = dir.resolve("src.jar");
        Files.writeString(source, "x");
        String sha = Hashing.sha256Hex(source);
        RepoArtifactStore store = new RepoArtifactStore(dir.resolve("cache"), "central");
        Path escapeTarget = dir.resolve("pwned.jar");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> store.materialize("../../../../pwned.jar", source, sha))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(escapeTarget).doesNotExist();
    }

    @Test
    void materialize_uses_a_unique_temp_and_leaves_no_shared_part_file(@TempDir Path dir) throws IOException {
        // The temp must not be a fixed "<name>.part" that concurrent writers of the same
        // artifact would share; and none should linger after a successful publish.
        Path source = dir.resolve("src.jar");
        Files.writeString(source, "artifact-bytes");
        String sha = Hashing.sha256Hex(source);
        RepoArtifactStore store = new RepoArtifactStore(dir.resolve("cache"), "central");
        String rel = "g/a/1/a-1.jar";
        store.materialize(rel, source, sha);

        Path artifactDir = dir.resolve("cache/repos/central/g/a/1");
        try (var s = Files.list(artifactDir)) {
            assertThat(s.map(p -> p.getFileName().toString()))
                    .noneMatch(n -> n.endsWith(".part"))
                    .contains("a-1.jar");
        }
        assertThat(store.verify(rel, sha)).isEqualTo(RepoArtifactStore.IndexState.VERIFIED);
    }
}
