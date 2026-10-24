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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactLocatorTest {

    private static final String CENTRAL = "central+https://repo.maven.apache.org/maven2/";

    private @Nullable String priorM2;

    @TempDir
    Path dir;

    /** The local repository every test in this class means by {@code ~/.m2}: the per-test directory it points at. */
    @BeforeEach
    void pointM2At() {
        priorM2 = System.getProperty("jk.m2.local");
        System.setProperty("jk.m2.local", dir.resolve("m2").toString());
    }

    @AfterEach
    void restoreM2() {
        if (priorM2 == null) System.clearProperty("jk.m2.local");
        else System.setProperty("jk.m2.local", priorM2);
    }

    /**
     * Central's jars live once, in the Maven local repository: a digest-matching file there is the
     * answer, at its own path, and the store keeps the memo that vouches for it and no copy.
     */
    @Test
    void a_central_artifact_is_answered_from_the_maven_local_repository_with_no_copy_in_the_store() throws Exception {
        Path store = dir.resolve("store");
        Path m2 = dir.resolve("m2");
        Lockfile.Artifact pkg = row(CENTRAL, "pending");
        String rel = MavenLayout.artifactPath(pkg.coordinate());
        Path m2Jar = m2.resolve(rel);
        Files.createDirectories(m2Jar.getParent());
        Files.writeString(m2Jar, "lib-bytes");
        String hex = Hashing.sha256Hex(m2Jar);
        pkg = row(CENTRAL, hex);

        ArtifactLocator locator = new ArtifactLocator(store, true);

        assertThat(locator.locate(pkg)).contains(m2Jar.toAbsolutePath().normalize());
        assertThat(store.resolve("repos/central").resolve(rel))
                .as("the store holds no second copy of the jar")
                .doesNotExist();
        assertThat(ArtifactMemo.jkPath(store.resolve("repos/central"), rel))
                .as("the store keeps the memo that vouches for the file in the local repository")
                .exists();
        assertThat(ArtifactMemo.read(ArtifactMemo.jkPath(store.resolve("repos/central"), rel)))
                .get()
                .extracting(ArtifactMemo::fetched)
                .as("a file jk only verified is not one it downloaded")
                .isEqualTo(false);
    }

    @Test
    void a_central_artifact_stays_in_its_store_tree_when_m2_integration_is_off() throws Exception {
        Path store = dir.resolve("store");
        Path m2 = dir.resolve("m2");
        String rel = "com/foo/a/1.0/a-1.0.jar";
        Path m2Jar = m2.resolve(rel);
        Files.createDirectories(m2Jar.getParent());
        Files.writeString(m2Jar, "in-m2");
        Path src = Files.writeString(dir.resolve("src.jar"), "in-store");
        String hex = Hashing.sha256Hex(src);
        RepoArtifactStore.forSource(store, CENTRAL, false).materialize(rel, src, hex);

        Lockfile.Artifact pkg = new Lockfile.Artifact(
                "com.foo:a", "1.0", CENTRAL, "sha256:" + hex, null, List.of(Scope.MAIN), List.of());

        assertThat(new ArtifactLocator(store, false).locate(pkg))
                .contains(store.resolve("repos/central")
                        .resolve(rel)
                        .toAbsolutePath()
                        .normalize());
    }

    @Test
    void a_locator_with_nothing_stored_answers_empty() {
        assertThat(new ArtifactLocator(dir.resolve("store"), true).locate(row(CENTRAL, "0".repeat(64))))
                .isEmpty();
    }

    /**
     * Two locks name a repository {@code private} for two different origins. The locator reads
     * each row's URL, not its name, so each project gets the bytes its own origin served — and
     * neither is Central or declared in settings.xml, so each keeps its own store tree.
     */
    @Test
    void two_lock_rows_naming_private_for_different_origins_read_different_stores() throws Exception {
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
        ArtifactLocator locator = new ArtifactLocator(store, true);

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

    /**
     * A file in the Maven local repository that is not what the row pins is not an answer, and is
     * left exactly as it is: it is Maven's or the user's, not jk's to replace or remove.
     */
    @Test
    void a_local_repository_file_that_does_not_match_the_pin_is_no_answer_and_stays_untouched() throws Exception {
        Path store = dir.resolve("store");
        Path m2 = dir.resolve("m2");
        String rel = "com/foo/a/1.0/a-1.0.jar";
        Path m2Jar = m2.resolve(rel);
        Files.createDirectories(m2Jar.getParent());
        Files.writeString(m2Jar, "poison");
        Lockfile.Artifact pkg = new Lockfile.Artifact(
                "com.foo:a", "1.0", CENTRAL, "sha256:" + "1".repeat(64), null, List.of(Scope.MAIN), List.of());

        assertThat(new ArtifactLocator(store, true).locate(pkg)).isEmpty();
        assertThat(Files.readString(m2Jar)).isEqualTo("poison");
    }

    /**
     * A lock row is a cloned project's text. A version carrying {@code ..} would resolve outside the
     * repository root, and a file planted there that matches the row's own checksum would come back
     * as a classpath entry; the row is refused before any path is built.
     */
    @Test
    void a_lock_row_with_dot_dot_in_the_version_is_refused() throws Exception {
        Path store = dir.resolve("store");
        Path planted = dir.resolve("evil.jar");
        Files.writeString(planted, "planted");
        String hex = Hashing.sha256Hex(planted);
        Lockfile.Artifact pkg = new Lockfile.Artifact(
                "com.foo:a", "../../../../evil.jar/x", CENTRAL, "sha256:" + hex, null, List.of(Scope.MAIN), List.of());

        ArtifactLocator locator = new ArtifactLocator(store, true);
        assertThatThrownBy(() -> locator.locate(pkg))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
        assertThat(dir.resolve("evil.jk")).doesNotExist();
    }

    @Test
    void a_relative_path_that_escapes_the_repository_root_is_refused_and_leaves_no_memo() throws Exception {
        Path store = dir.resolve("store");
        Files.createDirectories(dir.resolve("m2"));
        Path planted = dir.resolve("evil.jar");
        Files.writeString(planted, "planted");
        String hex = Hashing.sha256Hex(planted);

        ArtifactLocator locator = new ArtifactLocator(store, true);
        assertThatThrownBy(() -> locator.locate(CENTRAL, "../evil.jar", hex))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes");
        try (var files = Files.walk(dir)) {
            assertThat(files.filter(p -> p.getFileName().toString().endsWith(".jk")))
                    .as("no memo is written anywhere for a refused path")
                    .isEmpty();
        }
    }

    /**
     * A sources jar the lock did not pin is answered from wherever a sync left it, while a pinned
     * one is still held to its digest. For Central that is the Maven local repository.
     */
    @Test
    void an_unpinned_sources_jar_is_found_where_the_repository_keeps_it_and_a_pinned_one_is_verified()
            throws Exception {
        Path store = dir.resolve("store");
        Path m2 = dir.resolve("m2");
        Lockfile.Artifact pkg = new Lockfile.Artifact(
                "com.google.guava:guava", "33.4.8", CENTRAL, "sha256:pending", null, List.of(Scope.MAIN), List.of());
        String rel = "com/google/guava/guava/33.4.8/guava-33.4.8-sources.jar";
        ArtifactLocator locator = new ArtifactLocator(store, true);
        assertThat(locator.locateSources(pkg)).isEmpty();

        Path m2Sources = m2.resolve(rel);
        Files.createDirectories(m2Sources.getParent());
        Files.writeString(m2Sources, "sources-in-m2");
        assertThat(locator.locateSources(pkg))
                .contains(m2Sources.toAbsolutePath().normalize());

        String hex = Hashing.sha256Hex(m2Sources);
        assertThat(locator.locateSources(pkg.withSourcesChecksum("sha256:" + "0".repeat(64))))
                .as("a pin that matches nothing on disk")
                .isEmpty();
        assertThat(locator.locateSources(pkg.withSourcesChecksum("sha256:" + hex)))
                .contains(m2Sources.toAbsolutePath().normalize());
    }
}
