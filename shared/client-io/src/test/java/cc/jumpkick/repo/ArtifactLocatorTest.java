// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Scope;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactLocatorTest {

    /**
     * The Maven local repository is a read-through source, never an address: a digest-matching
     * file there is copied into the store and the store's path is the answer, so a build reads a
     * file only jk writes and another writer of the local repository cannot move it.
     */
    @Test
    void a_digest_matching_m2_file_is_materialized_into_the_store_and_answered_from_there(@TempDir Path dir)
            throws Exception {
        Path store = dir.resolve("store");
        Path m2 = dir.resolve("m2");
        Lockfile.Artifact pkg = new Lockfile.Artifact(
                "com.google.guava:guava",
                "33.4.8",
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:pending",
                null,
                List.of(Scope.MAIN),
                List.of());
        String rel = MavenLayout.artifactPath(pkg.coordinate());
        Path m2Jar = m2.resolve(rel);
        Files.createDirectories(m2Jar.getParent());
        Files.writeString(m2Jar, "guava-bytes");
        String hex = Hashing.sha256Hex(m2Jar);
        pkg = new Lockfile.Artifact(
                pkg.name(), pkg.version(), pkg.source(), "sha256:" + hex, pkg.path(), pkg.scopes(), pkg.deps());

        ArtifactLocator locator = new ArtifactLocator(store, m2, true);
        Path expected =
                store.resolve("repos/central").resolve(rel).toAbsolutePath().normalize();
        assertThat(locator.locate(pkg)).contains(expected);
        assertThat(expected).hasSameBinaryContentAs(m2Jar);

        // The m2 probe records its own `.m2.jk` memo, distinct from the store's `.jk`, so the two
        // blobs do not invalidate each other's fast path.
        Path storeSidecar = ArtifactMemo.jkPath(store.resolve("repos/central"), rel);
        Path m2Sidecar = storeSidecar.resolveSibling(
                storeSidecar.getFileName().toString().replace(".jk", ".m2.jk"));
        assertThat(m2Sidecar).exists();
        assertThat(storeSidecar).exists();
        // The mirror gone, the store still answers: the row is jk's now.
        Files.delete(m2Jar);
        assertThat(locator.locate(pkg)).contains(expected);
    }

    @Test
    void a_locator_with_no_mirror_answers_from_the_store_alone(@TempDir Path dir) {
        Lockfile.Artifact pkg = new Lockfile.Artifact(
                "org.tomlj:tomlj",
                "1.1.1",
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:" + "0".repeat(64),
                null,
                List.of(Scope.MAIN),
                List.of());
        assertThat(new ArtifactLocator(dir.resolve("store"), null, true).locate(pkg))
                .isEmpty();
    }

    /**
     * Two locks name a repository {@code private} for two different origins. The locator reads
     * each row's URL, not its name, so each project gets the bytes its own origin served.
     */
    @Test
    void two_lock_rows_naming_private_for_different_origins_read_different_stores(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("store");
        String rel = "com/acme/lib/1.0/lib-1.0.jar";
        Path fromA = Files.writeString(dir.resolve("a.jar"), "A");
        Path fromB = Files.writeString(dir.resolve("b.jar"), "B");
        String hexA = Hashing.sha256Hex(fromA);
        String hexB = Hashing.sha256Hex(fromB);
        RepoArtifactStore.forRepository(store, "private", URI.create("https://a.example/maven/"))
                .materialize(rel, fromA, hexA);
        RepoArtifactStore.forRepository(store, "private", URI.create("https://b.example/maven/"))
                .materialize(rel, fromB, hexB);
        ArtifactLocator locator = new ArtifactLocator(store);

        Path a = locator.locate(row("private+https://a.example/maven/", hexA)).orElseThrow();
        Path b = locator.locate(row("private+https://b.example/maven/", hexB)).orElseThrow();

        assertThat(a).hasContent("A");
        assertThat(b).hasContent("B");
        assertThat(a.getParent()).isNotEqualTo(b.getParent());
        assertThat(locator.locate(row("private+https://a.example/maven/", hexB)))
                .as("B's bytes are not served for A's origin even under the shared name")
                .isEmpty();
    }

    private static Lockfile.Artifact row(String source, String hex) {
        return new Lockfile.Artifact(
                "com.acme:lib", "1.0", source, "sha256:" + hex, null, List.of(Scope.MAIN), List.of());
    }

    @Test
    void mismatching_m2_falls_back_to_store_and_leaves_m2_untouched(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("store");
        Path m2 = dir.resolve("m2");
        String rel = "com/foo/a/1.0/a-1.0.jar";
        Path m2Jar = m2.resolve(rel);
        Files.createDirectories(m2Jar.getParent());
        Files.writeString(m2Jar, "poison");

        Path src = dir.resolve("good.jar");
        Files.writeString(src, "genuine");
        String hex = Hashing.sha256Hex(src);
        RepoArtifactStore.forStoreId(store, "central").materialize(rel, src, hex);

        Lockfile.Artifact pkg = new Lockfile.Artifact(
                "com.foo:a",
                "1.0",
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:" + hex,
                null,
                List.of(Scope.MAIN),
                List.of());

        ArtifactLocator locator = new ArtifactLocator(store, m2, true);
        Path found = locator.locate(pkg).orElseThrow();
        assertThat(found)
                .isEqualTo(store.resolve("repos/central")
                        .resolve(rel)
                        .toAbsolutePath()
                        .normalize());
        assertThat(Files.readString(m2Jar)).isEqualTo("poison");
    }

    /**
     * A lock row is a cloned project's text. A version carrying {@code ..} would resolve the m2
     * probe and its memo outside both roots, and a file planted there that matches the row's own
     * checksum would come back as a classpath entry; the row is refused before any path is built.
     */
    @Test
    void a_lock_row_with_dot_dot_in_the_version_is_refused(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("store");
        Path m2 = dir.resolve("m2");
        Path planted = dir.resolve("evil.jar");
        Files.writeString(planted, "planted");
        String hex = Hashing.sha256Hex(planted);
        Lockfile.Artifact pkg = new Lockfile.Artifact(
                "com.foo:a",
                "../../../../evil.jar/x",
                "central+https://repo.maven.apache.org/maven2/",
                "sha256:" + hex,
                null,
                List.of(Scope.MAIN),
                List.of());

        ArtifactLocator locator = new ArtifactLocator(store, m2, true);
        assertThatThrownBy(() -> locator.locate(pkg))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
        assertThat(dir.resolve("evil.m2.jk")).doesNotExist();
    }

    @Test
    void a_relative_path_that_escapes_the_m2_root_is_refused_and_leaves_no_memo(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("store");
        Path m2 = Files.createDirectories(dir.resolve("m2"));
        Path planted = dir.resolve("evil.jar");
        Files.writeString(planted, "planted");
        String hex = Hashing.sha256Hex(planted);

        ArtifactLocator locator = new ArtifactLocator(store, m2, true);
        assertThatThrownBy(() -> locator.locate("central", "../evil.jar", hex, "com.foo:a:1.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes");
        try (var files = Files.walk(dir)) {
            assertThat(files.filter(p -> p.getFileName().toString().endsWith(".jk")))
                    .as("no memo is written anywhere for a refused path")
                    .isEmpty();
        }
    }

    /**
     * A sources jar the lock did not pin is answered from wherever a sync left it — the store, or
     * the Maven local repository — while a pinned one is still held to its digest.
     */
    @Test
    void an_unpinned_sources_jar_is_found_in_the_store_or_m2_and_a_pinned_one_is_verified(@TempDir Path dir)
            throws Exception {
        Path store = dir.resolve("store");
        Path m2 = dir.resolve("m2");
        String source = "central+https://repo.maven.apache.org/maven2/";
        Lockfile.Artifact pkg = new Lockfile.Artifact(
                "com.google.guava:guava", "33.4.8", source, "sha256:pending", null, List.of(Scope.MAIN), List.of());
        String rel = "com/google/guava/guava/33.4.8/guava-33.4.8-sources.jar";
        ArtifactLocator locator = new ArtifactLocator(store, m2, true);
        assertThat(locator.locateSources(pkg)).isEmpty();

        Path m2Sources = m2.resolve(rel);
        Files.createDirectories(m2Sources.getParent());
        Files.writeString(m2Sources, "sources-in-m2");
        assertThat(locator.locateSources(pkg))
                .contains(m2Sources.toAbsolutePath().normalize());

        Path fetched = dir.resolve("fetched.jar");
        Files.writeString(fetched, "sources-in-store");
        RepoArtifactStore.forSource(store, source)
                .materialize(rel, fetched, Hashing.sha256Hex(Files.readAllBytes(fetched)));
        Path inStore = RepoArtifactStore.forSource(store, source).locate(rel).orElseThrow();
        assertThat(locator.locateSources(pkg)).contains(inStore.toAbsolutePath().normalize());

        Lockfile.Artifact pinned = pkg.withSourcesChecksum("sha256:" + "0".repeat(64));
        assertThat(locator.locateSources(pinned))
                .as("a pin that matches nothing on disk")
                .isEmpty();
        assertThat(locator.locateSources(
                        pkg.withSourcesChecksum("sha256:" + Hashing.sha256Hex(Files.readAllBytes(fetched)))))
                .contains(inStore.toAbsolutePath().normalize());
    }
}
