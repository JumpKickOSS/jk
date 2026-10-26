// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk release}: tag the declared version, then move {@code jk.toml} to the next one. */
@Tag("integration")
class ReleaseCommandTest {

    private static final String MANIFEST = """
            group     = "com.example"
            name      = "rel-lib"
            version   = "%s"
            jdk       = 25
            java      = 25
            """;

    private static int jk(Path dir, Path m2, String... verbAndFlags) {
        List<String> args = new ArrayList<>(List.of(verbAndFlags));
        args.addAll(List.of(
                "-C",
                dir.toString(),
                "--cache-dir",
                dir.resolve("cache").toString(),
                "--state-dir",
                dir.resolve("state").toString(),
                "--bin-dir",
                dir.resolve("bin").toString(),
                "--lib-dir",
                dir.resolve("lib").toString(),
                "--m2-dir",
                m2.toString()));
        return Jk.execute(args.toArray(String[]::new));
    }

    private static String git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(p.waitFor()).as(String.join(" ", command) + "\n" + out).isZero();
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

    @Test
    void publish_and_push_send_the_release_where_it_belongs(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectory(tmp.resolve("project"));
        Path m2 = repository(project, "1.4.7");
        Path remote = Files.createDirectory(tmp.resolve("remote.git"));
        git(remote, "init", "-q", "--bare", "-b", "main");
        git(project, "remote", "add", "origin", remote.toString());
        git(project, "push", "-q", "-u", "origin", "main");
        Map<String, byte[]> received = new HashMap<>();
        HttpServer repo = repositoryStub(received, 201);
        try {
            String url = "http://127.0.0.1:" + repo.getAddress().getPort() + "/repo/";

            assertThat(jk(project, m2, "release", "--publish", "--push", "--repo-url", url))
                    .isZero();

            assertThat(received).containsKey("/repo/com/example/rel-lib/1.4.7/rel-lib-1.4.7.jar");
            assertThat(git(remote, "tag", "--list")).isEqualTo("v1.4.7");
            assertThat(git(remote, "log", "-1", "--format=%s", "main")).isEqualTo("Start 1.4.8");
        } finally {
            repo.stop(0);
        }
    }

    @Test
    void a_failed_publish_leaves_no_tag_and_no_bump(@TempDir Path tmp) throws Exception {
        Path m2 = repository(tmp, "1.4.7");
        String manifest = Files.readString(tmp.resolve("jk.toml"));
        HttpServer repo = repositoryStub(new HashMap<>(), 500);
        try {
            String url = "http://127.0.0.1:" + repo.getAddress().getPort() + "/repo/";

            assertThat(jk(tmp, m2, "release", "--publish", "--repo-url", url)).isNotZero();

            assertThat(git(tmp, "tag", "--list")).isEmpty();
            assertThat(Files.readString(tmp.resolve("jk.toml"))).isEqualTo(manifest);
            assertThat(git(tmp, "status", "--porcelain")).isEmpty();
        } finally {
            repo.stop(0);
        }
    }

    @Test
    void a_failed_push_reports_what_was_done_and_the_command_to_finish(@TempDir Path tmp) throws Exception {
        Path m2 = repository(tmp, "1.4.7");
        git(tmp, "remote", "add", "origin", tmp.resolve("missing.git").toString());

        assertThat(jk(tmp, m2, "release", "--push")).isNotZero();

        assertThat(git(tmp, "tag", "--list")).as("the local release stands").isEqualTo("v1.4.7");
        assertThat(git(tmp, "log", "-1", "--format=%s")).isEqualTo("Start 1.4.8");
    }
}
