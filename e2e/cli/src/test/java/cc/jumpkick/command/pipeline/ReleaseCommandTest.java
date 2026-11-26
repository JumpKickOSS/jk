// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.host.GitCommand;
import cc.jumpkick.host.Processes;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk release}: tag the declared version, then move {@code jk.toml} to the next one. */
@Tag("release")
class ReleaseCommandTest {

    private static final String MANIFEST = """
            group     = "com.example"
            name      = "rel-lib"
            version   = "%s"
            jdk       = { version = "25" }
            java      = 25
            """;

    private static int jk(Path dir, Path m2, String... verbAndFlags) {
        List<String> args = new ArrayList<>(List.of(verbAndFlags));
        args.addAll(List.of(
                "-C",
                dir.toString(),
                "--state-dir",
                dir.resolve("state").toString(),
                "--bin-dir",
                dir.resolve("bin").toString(),
                "--lib-dir",
                dir.resolve("lib").toString(),
                "--m2-dir",
                m2.toString(),
                // These releases are checked in the Maven local repository, which an install targets by name.
                "--destination",
                "m2"));
        // The cache directory is the environment's, not an option of a release.
        System.setProperty("jk.env.JK_CACHE_DIR", dir.resolve("cache").toString());
        try {
            return Jk.execute(args.toArray(String[]::new));
        } finally {
            System.clearProperty("jk.env.JK_CACHE_DIR");
        }
    }

    private static String git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = GitCommand.of();
        command.addAll(List.of(args));
        Processes.Captured run = Processes.capture(
                new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true), Duration.ofMinutes(1));
        String out = run.output().strip();
        assertThat(run.exit()).as(String.join(" ", command) + "\n" + out).isZero();
        return out;
    }

    /** A committed library at {@code version}, with its lock, in a git repository. */
    private static Path repository(Path dir, String version) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/com/example"));
        Files.writeString(dir.resolve("jk.toml"), MANIFEST.formatted(version));
        Files.writeString(
                dir.resolve("src/main/java/com/example/Lib.java"), "package com.example; public final class Lib { }\n");
        Files.writeString(dir.resolve(".gitignore"), "cache/\nstate/\nbin/\nlib/\nm2/\ntarget/\n");
        Path m2 = dir.resolve("m2");
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "Ada");
        git(dir, "config", "user.email", "ada@example.com");
        assertThat(Jk.execute(
                        "lock",
                        "-C",
                        dir.toString(),
                        "--cache-dir",
                        dir.resolve("cache").toString()))
                .isZero();
        git(dir, "add", "-A");
        git(dir, "commit", "-q", "-m", "Start");
        return m2;
    }

    @Test
    void a_release_tags_the_declared_version_and_starts_the_next(@TempDir Path tmp) throws Exception {
        Path m2 = repository(tmp, "1.4.7");
        String released = git(tmp, "rev-parse", "HEAD");

        assertThat(jk(tmp, m2, "release")).isZero();

        assertThat(git(tmp, "rev-list", "-n", "1", "v1.4.7"))
                .as("the tag is on the release commit")
                .isEqualTo(released);
        assertThat(Files.readString(tmp.resolve("jk.toml"))).contains("version   = \"1.4.8\"");
        assertThat(git(tmp, "log", "-1", "--format=%s")).isEqualTo("Start 1.4.8");
        assertThat(git(tmp, "status", "--porcelain"))
                .as("the bump is committed")
                .isEmpty();
        assertThat(m2.resolve("repository/com/example/rel-lib/1.4.7/rel-lib-1.4.7.jar"))
                .as("the release is installed once")
                .exists();
    }

    @Test
    void files_listed_under_release_move_with_the_version(@TempDir Path tmp) throws Exception {
        Path m2 = repository(tmp, "1.4.7");
        Files.writeString(tmp.resolve("jk.toml"), Files.readString(tmp.resolve("jk.toml")) + """

                [release]
                files = [{ path = "FLOOR.txt", pattern = 'floor="([^"]+)"' }]
                """);
        Files.writeString(tmp.resolve("FLOOR.txt"), "floor=\"1.4.7\"\n");
        git(tmp, "add", "-A");
        git(tmp, "commit", "-q", "-m", "Pin the floor");

        assertThat(jk(tmp, m2, "release")).isZero();

        assertThat(Files.readString(tmp.resolve("FLOOR.txt"))).isEqualTo("floor=\"1.4.8\"\n");
        assertThat(git(tmp, "status", "--porcelain"))
                .as("the pinned file is in the bump commit")
                .isEmpty();
        assertThat(git(tmp, "show", "--stat", "--format=%s", "HEAD")).contains("FLOOR.txt");
    }

    @Test
    void the_segment_flag_picks_what_moves_after_the_release(@TempDir Path tmp) throws Exception {
        Path m2 = repository(tmp, "1.4.7");

        assertThat(jk(tmp, m2, "release", "--major")).isZero();

        assertThat(Files.readString(tmp.resolve("jk.toml"))).contains("version   = \"2.0.0\"");
        assertThat(git(tmp, "tag", "--list")).isEqualTo("v1.4.7");
    }

    @Test
    void a_dirty_tree_an_existing_tag_and_a_snapshot_version_are_refused(@TempDir Path tmp) throws Exception {
        Path m2 = repository(tmp, "1.4.7");

        Files.writeString(tmp.resolve("notes.txt"), "wip");
        assertThat(jk(tmp, m2, "release")).as("untracked work in the tree").isNotZero();
        Files.delete(tmp.resolve("notes.txt"));

        git(tmp, "tag", "v1.4.7");
        assertThat(jk(tmp, m2, "release")).as("the tag exists").isNotZero();
        git(tmp, "tag", "-d", "v1.4.7");

        Files.writeString(tmp.resolve("jk.toml"), MANIFEST.formatted("1.4.7-SNAPSHOT"));
        git(tmp, "commit", "-q", "-am", "Snapshot");
        assertThat(jk(tmp, m2, "release")).as("a snapshot cannot be released").isNotZero();

        assertThat(git(tmp, "tag", "--list")).isEmpty();
        assertThat(m2.resolve("repository/com/example/rel-lib")).doesNotExist();
    }

    @Test
    void a_dry_run_changes_nothing(@TempDir Path tmp) throws Exception {
        Path m2 = repository(tmp, "1.4.7");
        String head = git(tmp, "rev-parse", "HEAD");

        assertThat(jk(tmp, m2, "release", "--dry-run")).isZero();

        assertThat(git(tmp, "rev-parse", "HEAD")).isEqualTo(head);
        assertThat(git(tmp, "tag", "--list")).isEmpty();
        assertThat(m2.resolve("repository/com/example/rel-lib")).doesNotExist();
    }

    /** A repository stub that stores what is PUT and answers {@code putStatus} to every upload. */
    private static HttpServer repositoryStub(Map<String, byte[]> received, int putStatus) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("PUT".equals(exchange.getRequestMethod())) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                if (putStatus < 300) received.put(path, body);
                exchange.sendResponseHeaders(putStatus, -1);
            } else {
                byte[] body = received.get(path);
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    /** Declare {@code url} as the repository releases go to, unsigned and without sidecars, and commit it. */
    private static void publishTo(Path project, String url) throws Exception {
        Files.writeString(
                project.resolve("jk.toml"), Files.readString(project.resolve("jk.toml")) + """

                        [publish]
                        repository = "%s"
                        sign = false
                        sbom = false
                        provenance = false
                        """.formatted(url));
        git(project, "commit", "-q", "-am", "Publish to the stub");
    }

    private static Path withRemote(Path tmp, Path project) throws Exception {
        Path remote = Files.createDirectory(tmp.resolve("remote.git"));
        git(remote, "init", "-q", "--bare", "-b", "main");
        git(project, "remote", "add", "origin", remote.toString());
        git(project, "push", "-q", "-u", "origin", "main");
        return remote;
    }

    @Test
    void a_release_with_a_repository_and_no_ci_pushes_and_publishes_the_tag(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectory(tmp.resolve("project"));
        Path m2 = repository(project, "1.4.7");
        Path remote = withRemote(tmp, project);
        Map<String, byte[]> received = new HashMap<>();
        HttpServer repo = repositoryStub(received, 201);
        try {
            publishTo(project, "http://127.0.0.1:" + repo.getAddress().getPort() + "/repo/");
            git(project, "push", "-q");

            assertThat(jk(project, m2, "release")).isZero();

            assertThat(received).containsKey("/repo/com/example/rel-lib/1.4.7/rel-lib-1.4.7.jar");
            assertThat(git(remote, "tag", "--list")).isEqualTo("v1.4.7");
            assertThat(git(remote, "log", "-1", "--format=%s", "main")).isEqualTo("Start 1.4.8");
        } finally {
            repo.stop(0);
        }
    }

    @Test
    void ci_that_runs_jk_publish_is_left_to_publish(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectory(tmp.resolve("project"));
        Path m2 = repository(project, "1.4.7");
        Path remote = withRemote(tmp, project);
        Map<String, byte[]> received = new HashMap<>();
        HttpServer repo = repositoryStub(received, 201);
        try {
            publishTo(project, "http://127.0.0.1:" + repo.getAddress().getPort() + "/repo/");
            Files.createDirectories(project.resolve(".github/workflows"));
            Files.writeString(
                    project.resolve(".github/workflows/publish.yml"),
                    "jobs:\n  p:\n    steps:\n      - run: jk publish\n");
            git(project, "add", ".github");
            git(project, "commit", "-q", "-m", "CI publishes");
            git(project, "push", "-q");

            String out =
                    Capture.stdout(() -> assertThat(jk(project, m2, "release")).isZero());

            assertThat(out).contains("pushed v1.4.7; CI publishes it");
            assertThat(received).as("this machine uploaded nothing").isEmpty();
            assertThat(git(remote, "tag", "--list")).isEqualTo("v1.4.7");
        } finally {
            repo.stop(0);
        }
    }

    @Test
    void ci_publish_cannot_be_forced_without_pushing_the_tag(@TempDir Path tmp) throws Exception {
        Path m2 = repository(tmp, "1.4.7");

        assertThat(jk(tmp, m2, "release", "--publish", "ci", "--no-push")).isNotZero();

        assertThat(git(tmp, "tag", "--list")).isEmpty();
    }

    @Test
    void no_push_keeps_the_release_local(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectory(tmp.resolve("project"));
        Path m2 = repository(project, "1.4.7");
        Path remote = withRemote(tmp, project);

        assertThat(jk(project, m2, "release", "--no-push")).isZero();

        assertThat(git(project, "tag", "--list")).isEqualTo("v1.4.7");
        assertThat(git(remote, "tag", "--list")).isEmpty();
    }

    @Test
    void a_local_release_with_no_credential_is_refused_with_nothing_changed(@TempDir Path tmp) throws Exception {
        Path m2 = repository(tmp, "1.4.7");
        Files.writeString(
                tmp.resolve("jk.toml"),
                Files.readString(tmp.resolve("jk.toml")) + "\n[publish]\nrepository = \"central\"\n");
        git(tmp, "commit", "-q", "-am", "Publish to Central");
        String head = git(tmp, "rev-parse", "HEAD");

        assertThat(jk(tmp, m2, "release")).isNotZero();

        assertThat(git(tmp, "tag", "--list")).isEmpty();
        assertThat(git(tmp, "rev-parse", "HEAD")).isEqualTo(head);
    }

    @Test
    void a_failed_upload_after_the_push_leaves_the_pushed_tag_for_jk_publish_to_finish(@TempDir Path tmp)
            throws Exception {
        Path project = Files.createDirectory(tmp.resolve("project"));
        Path m2 = repository(project, "1.4.7");
        Path remote = withRemote(tmp, project);
        HttpServer repo = repositoryStub(new HashMap<>(), 500);
        try {
            publishTo(project, "http://127.0.0.1:" + repo.getAddress().getPort() + "/repo/");
            git(project, "push", "-q");

            assertThat(jk(project, m2, "release")).isNotZero();

            assertThat(git(project, "tag", "--list")).isEqualTo("v1.4.7");
            assertThat(git(remote, "tag", "--list")).isEqualTo("v1.4.7");
        } finally {
            repo.stop(0);
        }
    }

    @Test
    void a_failed_push_reports_what_was_done_and_the_command_to_finish(@TempDir Path tmp) throws Exception {
        Path m2 = repository(tmp, "1.4.7");
        git(tmp, "remote", "add", "origin", tmp.resolve("missing.git").toString());

        assertThat(jk(tmp, m2, "release")).isNotZero();

        assertThat(git(tmp, "tag", "--list")).as("the local release stands").isEqualTo("v1.4.7");
        assertThat(git(tmp, "log", "-1", "--format=%s")).isEqualTo("Start 1.4.8");
    }

    @Test
    void undo_takes_back_a_local_release_so_its_snapshot_installs_again(@TempDir Path tmp) throws Exception {
        Path m2 = repository(tmp, "1.4.7");
        String released = git(tmp, "rev-parse", "HEAD");
        assertThat(jk(tmp, m2, "release")).isZero();
        Path jar = m2.resolve("repository/com/example/rel-lib/1.4.7/rel-lib-1.4.7.jar");
        assertThat(jar).exists();
        Files.writeString(tmp.resolve("jk.toml"), MANIFEST.formatted("1.4.7"));
        git(tmp, "commit", "-q", "-am", "Back to 1.4.7 by hand");
        assertThat(jk(tmp, m2, "install", "-S"))
                .as("the installed release hides its snapshot")
                .isNotZero();
        git(tmp, "reset", "-q", "--hard", "HEAD^");

        assertThat(jk(tmp, m2, "release", "--undo")).isZero();

        assertThat(git(tmp, "tag", "--list")).as("the tag is gone").isEmpty();
        assertThat(git(tmp, "rev-parse", "HEAD"))
                .as("the version commit is gone")
                .isEqualTo(released);
        assertThat(Files.readString(tmp.resolve("jk.toml"))).contains("version   = \"1.4.7\"");
        assertThat(git(tmp, "status", "--porcelain")).isEmpty();
        assertThat(jar).as("the installed release is gone").doesNotExist();
        assertThat(jk(tmp, m2, "install", "-S")).isZero();
        assertThat(m2.resolve("repository/com/example/rel-lib/1.4.7-SNAPSHOT/rel-lib-1.4.7-SNAPSHOT.jar"))
                .exists();
        assertThat(jk(tmp, m2, "release", "--undo"))
                .as("nothing left to take back")
                .isNotZero();
    }

    @Test
    void undo_refuses_a_release_whose_tag_was_pushed(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectory(tmp.resolve("project"));
        Path m2 = repository(project, "1.4.7");
        Path remote = Files.createDirectory(tmp.resolve("remote.git"));
        git(remote, "init", "-q", "--bare", "-b", "main");
        git(project, "remote", "add", "origin", remote.toString());
        git(project, "push", "-q", "-u", "origin", "main");
        assertThat(jk(project, m2, "release")).isZero();
        String head = git(project, "rev-parse", "HEAD");
        git(project, "push", "-q", "origin", "v1.4.7");

        assertThat(jk(project, m2, "release", "--undo")).isNotZero();

        assertThat(git(project, "tag", "--list")).isEqualTo("v1.4.7");
        assertThat(git(project, "rev-parse", "HEAD")).isEqualTo(head);
        assertThat(m2.resolve("repository/com/example/rel-lib/1.4.7/rel-lib-1.4.7.jar"))
                .exists();

        git(project, "push", "-q", "origin", ":refs/tags/v1.4.7", "main");
        assertThat(jk(project, m2, "release", "--undo"))
                .as("the version commit is pushed")
                .isNotZero();
        assertThat(git(project, "tag", "--list")).isEqualTo("v1.4.7");
    }
}
