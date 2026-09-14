// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
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

    /**
     * Two projects both call a repository {@code private} and point it at different origins. Each
     * gets its own store, so a coordinate resolved through one never serves the other's bytes.
     */
    @Test
    void two_repositories_sharing_a_name_but_not_an_origin_get_separate_stores(@TempDir Path dir) throws IOException {
        Path store = dir.resolve("store");
        String rel = "com/acme/lib/1.0/lib-1.0.jar";
        Path fromA = Files.writeString(dir.resolve("a.jar"), "bytes served by origin A");
        Path fromB = Files.writeString(dir.resolve("b.jar"), "bytes served by origin B");
        RepoArtifactStore a = RepoArtifactStore.forRepository(store, "private", URI.create("https://a.example/maven/"));
        RepoArtifactStore b = RepoArtifactStore.forRepository(store, "private", URI.create("https://b.example/maven/"));

        a.materialize(rel, fromA, Hashing.sha256Hex(fromA));
        b.materialize(rel, fromB, Hashing.sha256Hex(fromB));

        assertThat(a.root()).isNotEqualTo(b.root());
        assertThat(a.locate(rel).orElseThrow()).hasContent("bytes served by origin A");
        assertThat(b.locate(rel).orElseThrow()).hasContent("bytes served by origin B");
        assertThat(store.resolve("repos/private"))
                .as("the name is a label, not a directory")
                .doesNotExist();
        RepoArtifactStore.Origin originA = Objects.requireNonNull(a.origin());
        assertThat(originA.name()).isEqualTo("private");
        assertThat(originA.origin()).isEqualTo("https://a.example/maven");
        assertThat(originA.isLegacy()).isFalse();
    }

    @Test
    void one_origin_under_two_names_is_one_store(@TempDir Path dir) throws IOException {
        Path store = dir.resolve("store");
        RepoArtifactStore corp = RepoArtifactStore.forRepository(store, "corp", URI.create("https://nexus.acme/maven"));
        RepoArtifactStore mirror =
                RepoArtifactStore.forRepository(store, "mirror", URI.create("https://NEXUS.acme:443/maven/"));

        assertThat(corp.root()).isEqualTo(mirror.root());
    }

    @Test
    void a_lock_source_resolves_to_the_store_of_its_url(@TempDir Path dir) {
        Path store = dir.resolve("store");

        assertThat(RepoArtifactStore.forSource(store, "central+https://repo.maven.apache.org/maven2/")
                        .root())
                .isEqualTo(store.resolve("repos/central"));
        assertThat(RepoArtifactStore.forSource(store, "private+https://a.example/maven/")
                        .root())
                .isEqualTo(RepoArtifactStore.forRepository(store, "x", URI.create("https://a.example/maven"))
                        .root());
        assertThat(RepoArtifactStore.forSource(store, "jk-local").root()).isEqualTo(store.resolve("repos/jk-local"));
        assertThat(RepoArtifactStore.forSource(store, "git:com.acme:lib:1.0+file:///tmp/x")
                        .root())
                .as("a synthetic source reads the first-party shelf")
                .isEqualTo(store.resolve("repos/jk-local"));
    }

    @Test
    void a_repository_served_out_of_the_store_is_that_tree_and_not_a_copy(@TempDir Path dir) {
        Path store = dir.resolve("store");
        Path central = store.resolve("repos/central");

        assertThat(RepoArtifactStore.forRepository(store, "central", central.toUri())
                        .root())
                .isEqualTo(central);
    }

    /**
     * A tree keyed by a repository name predates identity keying and nothing can say which origin
     * filled it: it is listed as legacy, excluded from every lookup, and named for removal. The
     * reserved public trees and the first-party shelf are adopted as they are.
     */
    @Test
    void a_name_keyed_tree_is_legacy_while_reserved_and_identity_keyed_trees_are_known(@TempDir Path dir)
            throws IOException {
        Path store = dir.resolve("store");
        Files.createDirectories(store.resolve("repos/central/g/a/1"));
        Files.createDirectories(store.resolve("repos/jk-local/g/a/1"));
        Files.createDirectories(store.resolve("repos/private/g/a/1"));
        Path src = Files.writeString(dir.resolve("src.jar"), "x");
        RepoArtifactStore.forRepository(store, "corp", URI.create("https://nexus.acme/maven"))
                .materialize("g/a/1/a-1.jar", src, Hashing.sha256Hex(src));

        List<RepoArtifactStore.Origin> all = RepoArtifactStore.describeAll(store);

        assertThat(all)
                .extracting(RepoArtifactStore.Origin::id, RepoArtifactStore.Origin::isLegacy)
                .contains(
                        tuple("central", false),
                        tuple("jk-local", false),
                        tuple("private", true),
                        tuple(RepoIdentity.storeId(URI.create("https://nexus.acme/maven")), false));
        assertThat(all.stream()
                        .filter(o -> o.id().equals("central"))
                        .findFirst()
                        .orElseThrow()
                        .origin())
                .isEqualTo("https://repo.maven.apache.org/maven2");
        assertThat(RepoArtifactStore.storeIds(store)).doesNotContain("private").contains("central", "jk-local");
        assertThat(RepoArtifactStore.legacyStores(store)).containsExactly(store.resolve("repos/private"));
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

    @Test
    void a_memo_refresh_keeps_the_packager_while_the_bytes_stand_and_drops_it_when_they_change(@TempDir Path dir)
            throws IOException {
        Path store = dir.resolve("store");
        String rel = "cc/jumpkick/jk-foo/1.0/jk-foo-1.0.jar";
        Path built = dir.resolve("jk-foo-1.0.jar");
        Files.writeString(built, "shelved by the engine");
        String engine = "a".repeat(64);
        RepoArtifactStore.writeToLocalStore(store, rel, built, engine);
        RepoArtifactStore shelf = new RepoArtifactStore(store, "jk-local");
        Path shelved = store.resolve("repos/jk-local").resolve(rel);
        Path memo = ArtifactMemo.jkPath(store.resolve("repos/jk-local"), rel);
        assertThat(ArtifactMemo.read(memo).orElseThrow().packagedBy()).isEqualTo(engine);

        // The resolver re-verifies the shelved jar (a Maven-local write-through, a re-hash): same bytes.
        shelf.writeMemo(rel, shelved, Hashing.sha256Hex(shelved));
        assertThat(ArtifactMemo.read(memo).orElseThrow().packagedBy()).isEqualTo(engine);

        // Different bytes under the same path are another artifact; nothing vouches for its packager.
        Files.writeString(shelved, "replaced from a remote");
        shelf.writeMemo(rel, shelved, Hashing.sha256Hex(shelved));
        assertThat(ArtifactMemo.read(memo).orElseThrow().packagedBy()).isNull();
    }
}
