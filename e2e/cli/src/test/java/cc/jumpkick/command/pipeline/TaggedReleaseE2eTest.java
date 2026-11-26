// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.host.GitCommand;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.Processes;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.model.command.Exit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Inside git, a clean checkout at the declared version's tag builds the release, named and stamped
 * as {@code jk release} builds it, and every other tree a snapshot; the two never serve each other's
 * jars, a tagged tree with changes says why it built a snapshot, and {@code jk publish} ships only a
 * release. Outside git, {@code jk release} and {@code jk publish} are releases by the verb and every
 * other verb builds a snapshot. An install or a release that names no destination lands in the
 * store's {@code repos/jk-local}.
 */
@Tag("integration")
class TaggedReleaseE2eTest {

    /** The group of every install these tests make, one subgroup per test. */
    private static final String GROUP = "zz.tagged";

    private static final String MANIFEST = """
            group     = "%s"
            name      = "rel-lib"
            version   = "1.4.7"
            java      = 25
            sources   = true

            [resources]
            filtered = ["src/filter/resources"]
            """;

    /**
     * What these tests installed in the sandbox's shelf, the one the engine writes, removed before
     * and after each test: a release left there would hide the next run's snapshot of it.
     */
    @BeforeEach
    @AfterEach
    void removeInstalls() throws IOException {
        Path installed = shelf(GROUP);
        if (Files.exists(installed)) PathUtil.deleteRecursivelyOrThrow(installed);
    }

    /** Where jk's shelf keeps {@code group}'s artifacts. */
    private static Path shelf(String group) {
        return JkStores.store()
                .resolve("repos")
                .resolve(RepositorySpec.JK_LOCAL)
                .resolve(group.replace('.', '/'));
    }

    private static int jk(Path dir, String... verbAndFlags) {
        List<String> args = new ArrayList<>(List.of(verbAndFlags));
        args.addAll(List.of("-C", dir.toString()));
        String previous = System.getProperty("jk.env.JK_CACHE_DIR");
        System.setProperty("jk.env.JK_CACHE_DIR", dir.resolve("cache").toString());
        try {
            return Jk.execute(args.toArray(String[]::new));
        } finally {
            if (previous == null) System.clearProperty("jk.env.JK_CACHE_DIR");
            else System.setProperty("jk.env.JK_CACHE_DIR", previous);
        }
    }

    private static String git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = GitCommand.of();
        command.addAll(List.of(args));
        Processes.Captured run = Processes.capture(
                new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true), Duration.ofMinutes(1));
        assertThat(run.exit())
                .as(String.join(" ", command) + "\n" + run.output())
                .isZero();
        return run.output().strip();
    }

    /** A library at 1.4.7 in {@code group} that stamps its version into a resource, locked, not yet in git. */
    private static Path project(Path dir, String group) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/com/example"));
        Files.writeString(dir.resolve("jk.toml"), MANIFEST.formatted(group));
        Files.writeString(
                dir.resolve("src/main/java/com/example/Lib.java"),
                "package com.example; /** A library. */ public final class Lib { }\n");
        Path res = Files.createDirectories(dir.resolve("src/filter/resources/rel"));
        Files.writeString(res.resolve("version.properties"), "version=${project.version}\n");
        assertThat(jk(dir, "lock")).isZero();
        return dir;
    }

    /** {@link #project} committed in a git repository. */
    private static Path repository(Path dir, String group) throws Exception {
        project(dir, group);
        Files.writeString(dir.resolve(".gitignore"), "cache/\nm2/\nrepo/\ntarget/\n");
        commit(dir);
        return dir;
    }

    private static void commit(Path dir) throws Exception {
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "Ada");
        git(dir, "config", "user.email", "ada@example.com");
        git(dir, "config", "commit.gpgsign", "false");
        git(dir, "config", "tag.gpgsign", "false");
        git(dir, "add", "-A");
        git(dir, "commit", "-q", "-m", "Start");
    }

    private static String stamp(Path jar) throws IOException {
        assertThat(jar).exists();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("rel/version.properties");
            assertThat(entry).as("the filtered resource in %s", jar).isNotNull();
            String body = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            return body.strip().substring("version=".length());
        }
    }

    /** What {@code body} printed on stdout and stderr. */
    private static String output(Runnable body) {
        String[] err = {""};
        String out = Capture.stdout(() -> err[0] = Capture.stderr(body));
        return out + err[0];
    }

    @Test
    void the_tag_decides_and_a_snapshot_and_a_release_never_share_a_jar(@TempDir Path tmp) throws Exception {
        Path dir = repository(tmp, "com.example");
        Path releaseJar = dir.resolve("target/rel-lib-1.4.7.jar");
        Path snapshotJar = dir.resolve("target/rel-lib-1.4.7-SNAPSHOT.jar");
        git(dir, "tag", "v1.4.7");

        String built = output(() -> assertThat(jk(dir, "build", "-S")).isZero());
        assertThat(built).contains("Release 1.4.7 (tag v1.4.7)");
        assertThat(stamp(releaseJar)).isEqualTo("1.4.7");
        assertThat(snapshotJar).doesNotExist();
        String released = Hashing.sha256Hex(releaseJar);

        String forecast = output(() -> assertThat(jk(dir, "explain", "-S")).isZero());
        assertThat(forecast).contains("1 module is fresh").contains("Release 1.4.7 (tag v1.4.7)");

        git(dir, "tag", "-d", "v1.4.7");
        String snapshotForecast =
                output(() -> assertThat(jk(dir, "explain", "-S")).isZero());
        assertThat(snapshotForecast)
                .as("a snapshot build reuses nothing the release packaged")
                .doesNotContain("1 module is fresh")
                .doesNotContain("Release 1.4.7");
        String snapshot = output(() -> assertThat(jk(dir, "build", "-S")).isZero());
        assertThat(snapshot).doesNotContain("Release 1.4.7");
        assertThat(stamp(snapshotJar)).isEqualTo("1.4.7-SNAPSHOT");
        assertThat(Hashing.sha256Hex(releaseJar)).isEqualTo(released);

        git(dir, "tag", "-a", "v1.4.7", "-m", "1.4.7");
        assertThat(jk(dir, "build", "-S")).isZero();
        assertThat(Hashing.sha256Hex(releaseJar))
                .as("the release rebuilt after a snapshot is the release")
                .isEqualTo(released);
        assertThat(stamp(snapshotJar)).isEqualTo("1.4.7-SNAPSHOT");

        Files.writeString(dir.resolve("NOTES.md"), "not committed\n");
        Files.delete(snapshotJar);
        String dirty = output(() -> assertThat(jk(dir, "build", "-S")).isZero());
        assertThat(dirty).contains("HEAD is v1.4.7 but the tree differs from it, so this build is a snapshot");
        assertThat(stamp(snapshotJar)).isEqualTo("1.4.7-SNAPSHOT");
        assertThat(Hashing.sha256Hex(releaseJar)).isEqualTo(released);
    }

    @Test
    void publish_ships_only_a_release_and_says_why_not(@TempDir Path tmp) throws Exception {
        Path dir = repository(tmp, "com.example");

        String untagged = output(() -> assertThat(Jk.execute("publish", "--dry-run", "-C", dir.toString()))
                .isEqualTo(Exit.CONFIG));
        assertThat(untagged).contains("HEAD carries no tag v1.4.7, so this is a snapshot");
        String snapshot =
                output(() -> assertThat(Jk.execute("publish", "--dry-run", "--allow-snapshot", "-C", dir.toString()))
                        .isZero());
        assertThat(snapshot)
                .as("--allow-snapshot publishes the untagged tree as its snapshot")
                .contains("1.4.7-SNAPSHOT");

        git(dir, "tag", "v1.4.6");
        String other = output(() -> assertThat(Jk.execute("publish", "--dry-run", "-C", dir.toString()))
                .isEqualTo(Exit.CONFIG));
        assertThat(other).contains("HEAD carries v1.4.6, not v1.4.7");

        git(dir, "tag", "v1.4.7");
        Files.writeString(dir.resolve("NOTES.md"), "not committed\n");
        String dirty = output(() -> assertThat(Jk.execute("publish", "--dry-run", "-C", dir.toString()))
                .isEqualTo(Exit.CONFIG));
        assertThat(dirty).contains("the tree differs from it").contains("commit or stash, then publish");
    }

    @Test
    void an_installed_release_hides_its_snapshot_and_the_refusal_names_the_way_back(@TempDir Path tmp)
            throws Exception {
        String group = GROUP + ".hidden";
        Path dir = repository(tmp, group);
        git(dir, "tag", "v1.4.7");
        Path m2 = dir.resolve("m2");
        Path slots = shelf(group).resolve("rel-lib");
        Path installed = slots.resolve("1.4.7/rel-lib-1.4.7.jar");

        assertThat(jk(dir, "install", "-S", "--m2-dir", m2.toString())).isZero();
        assertThat(stamp(installed)).as("a tagged tree installs its release").isEqualTo("1.4.7");

        Files.writeString(dir.resolve("NOTES.md"), "not committed\n");
        String refused = output(() ->
                assertThat(jk(dir, "install", "-S", "--m2-dir", m2.toString())).isNotZero());
        assertThat(refused)
                .contains("would be hidden by the release " + group + ":rel-lib:1.4.7")
                .contains("jk release --undo");

        Files.delete(dir.resolve("NOTES.md"));
        assertThat(jk(dir, "release", "--undo", "--m2-dir", m2.toString())).isZero();
        assertThat(installed).doesNotExist();
        assertThat(jk(dir, "install", "-S", "--m2-dir", m2.toString())).isZero();
        assertThat(stamp(slots.resolve("1.4.7-SNAPSHOT/rel-lib-1.4.7-SNAPSHOT.jar")))
                .isEqualTo("1.4.7-SNAPSHOT");
        assertThat(m2.resolve("repository/zz"))
                .as("nothing went into the Maven local repository")
                .doesNotExist();
    }

    @Test
    void outside_git_build_is_a_snapshot_and_release_and_publish_are_releases(@TempDir Path tmp) throws Exception {
        String group = GROUP + ".archive";
        Path dir = project(tmp.resolve("archive"), group);
        Path m2 = tmp.resolve("m2");

        assertThat(jk(dir, "build", "-S")).isZero();
        assertThat(stamp(dir.resolve("target/rel-lib-1.4.7-SNAPSHOT.jar"))).isEqualTo("1.4.7-SNAPSHOT");
        assertThat(dir.resolve("target/rel-lib-1.4.7.jar")).doesNotExist();

        Path repo = tmp.resolve("repo");
        String published = output(() -> assertThat(Jk.execute(
                        "publish",
                        "-C",
                        dir.toString(),
                        "--repository",
                        repo.toUri().toString()))
                .isZero());
        Path version = repo.resolve(group.replace('.', '/') + "/rel-lib/1.4.7");
        assertThat(version.resolve("rel-lib-1.4.7.jar")).as(published).exists();
        assertThat(stamp(version.resolve("rel-lib-1.4.7.jar"))).isEqualTo("1.4.7");
        assertThat(version.resolve("rel-lib-1.4.7-sources.jar")).exists();
        assertThat(version.resolve("rel-lib-1.4.7-javadoc.jar")).exists();
        assertThat(Files.readString(version.resolve("rel-lib-1.4.7.pom")))
                .contains("<version>1.4.7</version>")
                .doesNotContain("SNAPSHOT");
        try (Stream<Path> files = Files.walk(repo)) {
            assertThat(files.map(Path::toString)).noneMatch(n -> n.contains("SNAPSHOT"));
        }

        String released = output(() -> assertThat(jk(
                        dir,
                        "release",
                        "--skip-tests",
                        "--state-dir",
                        tmp.resolve("state").toString(),
                        "--bin-dir",
                        tmp.resolve("bin").toString(),
                        "--lib-dir",
                        tmp.resolve("lib").toString(),
                        "--m2-dir",
                        m2.toString()))
                .isZero());
        assertThat(released).contains("no git here: nothing tagged or committed");
        assertThat(stamp(shelf(group).resolve("rel-lib/1.4.7/rel-lib-1.4.7.jar")))
                .isEqualTo("1.4.7");
        assertThat(m2.resolve("repository/zz"))
                .as("nothing went into the Maven local repository")
                .doesNotExist();
        assertThat(Files.readString(dir.resolve("jk.toml"))).contains("version   = \"1.4.8\"");
        assertThat(dir.resolve(".git")).as("no repository was made").doesNotExist();

        String undo = output(() -> assertThat(jk(dir, "release", "--undo", "--m2-dir", m2.toString()))
                .isEqualTo(Exit.CONFIG));
        assertThat(undo).contains("no git here").contains("by hand");
    }

    /** The group of {@link #workspace}'s modules. */
    private static final String WS_GROUP = GROUP + ".ws";

    /** A workspace of a library and an application that bundles it, every artifact kind turned on. */
    private static Path workspace(Path tmp) throws Exception {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "%s"
                name    = "ws"
                version = "1.2.3"
                java    = 25

                [workspace]
                modules = ["lib", "app"]
                """.formatted(WS_GROUP));
        Path lib = Files.createDirectories(ws.resolve("lib/src/main/java/demo/lib"));
        Files.writeString(ws.resolve("lib/jk.toml"), """
                group   = "%s"
                name    = "lib"
                version = "1.2.3"
                java    = 25
                javadoc = true
                """.formatted(WS_GROUP));
        Files.writeString(lib.resolve("Lib.java"), "package demo.lib; /** A library. */ public final class Lib { }\n");
        Path app = Files.createDirectories(ws.resolve("app/src/main/java/demo/app"));
        Files.writeString(ws.resolve("app/jk.toml"), """
                group   = "%s"
                name    = "app"
                version = "1.2.3"
                java    = 25
                javadoc = true
                sources = "always"

                [application]
                main     = "demo.app.App"
                assembly = true

                [dependencies]
                lib = { workspace = true }
                """.formatted(WS_GROUP));
        Files.writeString(
                app.resolve("App.java"),
                "package demo.app; /** The app. */ public final class App { public static void main(String[] a) {"
                        + " new demo.lib.Lib(); } }\n");
        Files.writeString(ws.resolve(".gitignore"), "cache/\nm2/\ntarget/\n");
        assertThat(jk(ws, "lock")).isZero();
        commit(ws);
        return ws;
    }

    /** The jar names directly under {@code module}'s {@code target/}. */
    private static List<String> jars(Path ws, String module) throws IOException {
        try (var files = Files.list(ws.resolve(module).resolve("target"))) {
            return files.map(f -> f.getFileName().toString())
                    .filter(n -> n.endsWith(".jar"))
                    .sorted()
                    .toList();
        }
    }

    /** Every artifact of a build and an install carries {@code version} and no other. */
    private static void assertEveryArtifactIs(Path ws, Path m2, String version) throws IOException {
        Path installed = shelf(WS_GROUP);
        assertThat(jars(ws, "lib"))
                .contains(
                        "lib-" + version + ".jar", "lib-" + version + "-sources.jar", "lib-" + version + "-javadoc.jar")
                .allMatch(n -> n.matches("lib-" + Pattern.quote(version) + "(-sources|-javadoc)?\\.jar"));
        assertThat(jars(ws, "app"))
                .contains(
                        "app-" + version + ".jar",
                        "app-" + version + "-all.jar",
                        "app-" + version + "-sources.jar",
                        "app-" + version + "-javadoc.jar")
                .allMatch(n -> n.matches("app-" + Pattern.quote(version) + "(-sources|-javadoc|-all)?\\.jar"));
        Path pom = installed.resolve("app/" + version + "/app-" + version + ".pom");
        String body = Files.readString(pom);
        assertThat(body)
                .as("the POM names its own version and its sibling's")
                .contains("<version>" + version + "</version>")
                .contains("<artifactId>lib</artifactId>");
        String dep = body.substring(body.indexOf("<artifactId>lib</artifactId>"));
        assertThat(dep.substring(0, dep.indexOf("</dependency>"))).contains("<version>" + version + "</version>");
        if (!version.endsWith("-SNAPSHOT")) assertThat(body).doesNotContain("SNAPSHOT");
        assertThat(installed.resolve("lib/" + version + "/lib-" + version + ".pom"))
                .exists();
        assertThat(m2.resolve("repository/zz"))
                .as("nothing went into the Maven local repository")
                .doesNotExist();
    }

    @Test
    void every_artifact_of_a_module_carries_its_main_jars_version_in_both_kinds(@TempDir Path tmp) throws Exception {
        Path ws = workspace(tmp);
        Path m2 = ws.resolve("m2");

        assertThat(jk(ws, "install", "-S", "--m2-dir", m2.toString())).isZero();
        assertEveryArtifactIs(ws, m2, "1.2.3-SNAPSHOT");

        // A fresh clone of the tag: its target/ holds only what the release build writes.
        git(ws, "tag", "v1.2.3");
        Path release = tmp.resolve("release");
        git(tmp, "clone", "-q", "--branch", "v1.2.3", ws.toString(), release.toString());
        Path releaseM2 = release.resolve("m2");
        assertThat(jk(release, "install", "-S", "--m2-dir", releaseM2.toString()))
                .isZero();
        assertEveryArtifactIs(release, releaseM2, "1.2.3");
    }
}
