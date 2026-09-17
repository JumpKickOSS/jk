// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.PathSource;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.runtime.base.TestStoreSeed;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end (offline) materialization of a local-path jk dependency: build a jk.toml library on
 * disk, then resolve → build → local-publish, keyed by a content fingerprint (no git, no network).
 */
// Out of the unit tier: three full materialize -> build -> publish cycles, like its already-tagged siblings.
@Tag("integration")
class PathSourceMaterializerTest {

    /** Write a trivial no-dependency jk.toml library at {@code libDir}. */
    private static void writeLibrary(Path libDir, String returnValue) throws Exception {
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("jk.toml"), """
                group   = "com.acme"
                name    = "widgets"
                version = "0.1.0"
                jdk     = 25
                java    = 25
                """);
        Path src = libDir.resolve("src/main/java/acme/Widget.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                package acme;
                public class Widget { public static String name() { return "%s"; } }
                """.formatted(returnValue));
    }

    private static PathSourceMaterializer materializer(Path root, Path artifactsRoot) throws IOException {
        Cas cas = new Cas(root.resolve("cas"));
        TestStoreSeed.seed(JkDirs.store(), cas.root());
        RepoGroup buildRepos =
                RepoGroup.of(new MavenRepo("central", RepositorySpec.MAVEN_CENTRAL.url(), new Http(), cas));
        return new PathSourceMaterializer(
                root, artifactsRoot, cas, buildRepos, Path.of(System.getProperty("java.home")), "test");
    }

    @Test
    void materializes_a_jk_path_library_into_a_local_repo(@TempDir Path tmp) throws Exception {
        writeLibrary(tmp.resolve("lib"), "widget");
        var m = materializer(tmp, tmp.resolve("path-artifacts")).materialize(new PathSource("./lib"));

        assertThat(m.group()).isEqualTo("com.acme");
        assertThat(m.artifact()).isEqualTo("widgets");
        assertThat(m.version()).isEqualTo("0.1.0"); // path deps keep the declared version

        Path repoDir = Path.of(m.repoUrl());
        assertThat(repoDir.resolve("com/acme/widgets/0.1.0/widgets-0.1.0.jar")).exists();
        Path pom = repoDir.resolve("com/acme/widgets/0.1.0/widgets-0.1.0.pom");
        assertThat(pom).exists();
        assertThat(Files.readString(pom)).contains("<groupId>com.acme</groupId>");
    }

    @Test
    void is_idempotent_on_a_cache_hit(@TempDir Path tmp) throws Exception {
        writeLibrary(tmp.resolve("lib"), "widget");
        var materializer = materializer(tmp, tmp.resolve("path-artifacts"));
        var first = materializer.materialize(new PathSource("./lib"));
        var second = materializer.materialize(new PathSource("./lib"));
        assertThat(second.repoUrl()).isEqualTo(first.repoUrl()); // same fingerprint → same repo dir
    }

    @Test
    void rebuilds_when_the_source_content_changes(@TempDir Path tmp) throws Exception {
        writeLibrary(tmp.resolve("lib"), "widget");
        var materializer = materializer(tmp, tmp.resolve("path-artifacts"));
        var before = materializer.materialize(new PathSource("./lib"));

        writeLibrary(tmp.resolve("lib"), "gadget"); // mutate a source file
        var after = materializer.materialize(new PathSource("./lib"));

        assertThat(after.repoUrl()).isNotEqualTo(before.repoUrl()); // new fingerprint → new repo dir
        assertThat(Path.of(after.repoUrl()).resolve("com/acme/widgets/0.1.0/widgets-0.1.0.jar"))
                .exists();
    }

    /**
     * A jk target reads its GAV straight out of {@code jk.toml}, so the coordinate marker is for
     * foreign (Gradle/Maven) targets only — writing one for a jk target is output nothing reads.
     */
    @Test
    void a_jk_target_writes_no_coordinate_marker(@TempDir Path tmp) throws Exception {
        writeLibrary(tmp.resolve("lib"), "widget");
        Path artifactsRoot = tmp.resolve("path-artifacts");
        materializer(tmp, artifactsRoot).materialize(new PathSource("./lib"));

        try (var walk = Files.walk(artifactsRoot)) {
            assertThat(walk.filter(p -> p.getFileName().toString().equals("coordinate.txt")))
                    .isEmpty();
        }
    }

    /**
     * The marker is the only record of a foreign target's coordinate, and the fingerprint cache-hit
     * branch feeds what it parses straight into the artifact path. It round-trips or every foreign
     * target rebuilds forever: {@code coordinate()} already ends in the version, and appending it
     * again produced {@code g:a:v:v}, a path that can never exist.
     */
    @Test
    void the_coordinate_marker_round_trips_the_gav(@TempDir Path tmp) throws Exception {
        Path marker = tmp.resolve("coordinate.txt");
        var built = new SourceProjectBuilder.Built("com.acme", "widgets", "0.1.0", tmp.resolve("w.jar"), "<project/>");

        GitSourceMaterializer.writeCoordinateMarker(marker, built);

        assertThat(Files.readString(marker)).isEqualTo("com.acme:widgets:0.1.0");
        assertThat(GitSourceMaterializer.readCoordinateMarker(marker))
                .isEqualTo(new GitSourceMaterializer.Gav("com.acme", "widgets", "0.1.0"));
    }

    @Test
    void a_marker_that_is_not_a_three_part_coordinate_fails_loudly(@TempDir Path tmp) throws Exception {
        Path marker = tmp.resolve("coordinate.txt");
        Files.writeString(marker, "com.acme:widgets:0.1.0:0.1.0");

        assertThatThrownBy(() -> GitSourceMaterializer.readCoordinateMarker(marker))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("expected group:artifact:version");
    }

    @Test
    void fails_when_the_target_directory_is_missing(@TempDir Path tmp) throws Exception {
        assertThatThrownBy(
                        () -> materializer(tmp, tmp.resolve("path-artifacts")).materialize(new PathSource("./nope")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("does not resolve to a directory");
    }
}
