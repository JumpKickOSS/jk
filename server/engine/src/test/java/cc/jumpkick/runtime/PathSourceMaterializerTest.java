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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
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
                jdk     = { version = "25" }
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
        TestStoreSeed.seed(cas.root());
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

        // A worktree checked out below the target is another branch's tree, not the target's source.
        Path worktree = Files.createDirectories(tmp.resolve("lib/.claude/worktrees/one"));
        Files.writeString(worktree.resolve(".git"), "gitdir: elsewhere\n");
        writeLibrary(worktree, "elsewhere");
        var third = materializer.materialize(new PathSource("./lib"));
        assertThat(third.repoUrl()).isEqualTo(first.repoUrl());
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

    /**
     * A path dependency onto a module of another workspace: the module names only itself and
     * inherits group, version and java from its workspace root, so it publishes under the root's
     * group and version with its classes in the jar, never as the unresolved {@code
     * __jk.workspace__} placeholder a consumer's classpath cannot use.
     */
    @Test
    void a_member_of_another_workspace_publishes_under_its_inherited_coordinate(@TempDir Path tmp) throws Exception {
        Path libs = Files.createDirectories(tmp.resolve("libs"));
        Files.writeString(libs.resolve("jk.toml"), """
                group   = "com.acme.libs"
                name    = "libs"
                version = "2.3.0"
                java    = 25

                [workspace]
                modules = ["core"]
                """);
        Path core = Files.createDirectories(libs.resolve("core/src/main/java/acme/core"));
        Files.writeString(libs.resolve("core/jk.toml"), "name = \"core\"\n");
        Files.writeString(core.resolve("Core.java"), "package acme.core;\npublic class Core {}\n");

        var m = materializer(tmp, tmp.resolve("path-artifacts")).materialize(new PathSource("./libs/core"));

        assertThat(m.group()).isEqualTo("com.acme.libs");
        assertThat(m.artifact()).isEqualTo("core");
        assertThat(m.version()).isEqualTo("2.3.0");
        Path jar = Path.of(m.repoUrl()).resolve("com/acme/libs/core/2.3.0/core-2.3.0.jar");
        try (JarFile file = new JarFile(jar.toFile())) {
            assertThat(file.getEntry("acme/core/Core.class"))
                    .as("the member's classes")
                    .isNotNull();
        }
    }

    /** A member whose own edges name a sibling cannot be built alone: refused, naming the sibling and the fix. */
    @Test
    void a_member_that_depends_on_a_sibling_is_refused_naming_it(@TempDir Path tmp) throws Exception {
        Path libs = Files.createDirectories(tmp.resolve("libs"));
        Files.writeString(libs.resolve("jk.toml"), """
                group   = "com.acme.libs"
                name    = "libs"
                version = "2.3.0"
                java    = 25

                [workspace]
                modules = ["api", "core"]
                """);
        Files.createDirectories(libs.resolve("api/src/main/java/acme/api"));
        Files.writeString(libs.resolve("api/jk.toml"), "name = \"api\"\n");
        Files.writeString(
                libs.resolve("api/src/main/java/acme/api/Api.java"), "package acme.api;\npublic class Api {}\n");
        Files.createDirectories(libs.resolve("core/src/main/java/acme/core"));
        Files.writeString(libs.resolve("core/jk.toml"), "name = \"core\"\n\n[dependencies]\napi.workspace = true\n");
        Files.writeString(
                libs.resolve("core/src/main/java/acme/core/Core.java"), "package acme.core;\npublic class Core {}\n");

        assertThatThrownBy(() ->
                        materializer(tmp, tmp.resolve("path-artifacts")).materialize(new PathSource("./libs/core")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("depends on api of its own workspace")
                .hasMessageContaining("`com.acme.libs:core:2.3.0`");
    }
}
