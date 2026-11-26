// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.host.GitCommand;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Processes;
import cc.jumpkick.host.ReleasePlatform;
import cc.jumpkick.jsonl.MiniJson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: the release lane against a loopback GitLab API. A tagged single-platform application
 * whose origin is a self-managed GitLab project in a subgroup stages its runtime image into the
 * generic package with no release yet; the final run uploads the fat jar, SHA256SUMS and
 * index.json and creates the release linking every file; a second final run changes nothing. A
 * final run that lacks a listed platform is refused before anything builds, and so are an origin
 * on another forge and a token without the scope.
 */
@Tag("integration")
class ReleaseGitLabPublishTest {

    private static final String HOST = ReleasePlatform.current();
    private static final String TOKEN = "e2e-gitlab-release-token";
    private static final String PROJECT = "/api/v4/projects/acme%2Ftools%2Fapp";
    private static final String[] ENV = {"JK_GITLAB_TOKEN"};

    /** The generic package {@code app/1.0.0}'s files by name, oldest first. */
    private final Map<String, byte[]> files = new LinkedHashMap<>();

    /** The release's links by name, once it exists. */
    private volatile @Nullable Map<String, String> links;

    private final AtomicInteger writes = new AtomicInteger();
    private volatile int refuse;
    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        System.setProperty("jk.env.JK_GITLAB_TOKEN", TOKEN);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        for (String name : ENV) System.clearProperty("jk.env." + name);
    }

    private String web() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Generic packages listed, their files listed and uploaded; the release read and created. */
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (refuse != 0) {
                send(exchange, refuse, "{\"message\":\"403 Forbidden\"}");
                return;
            }
            if (!("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                send(exchange, 401, "{\"message\":\"401 Unauthorized\"}");
                return;
            }
            String method = exchange.getRequestMethod();
            String raw = exchange.getRequestURI().getRawPath();
            byte[] body = exchange.getRequestBody().readAllBytes();
            synchronized (files) {
                if (raw.equals(PROJECT + "/packages") && method.equals("GET")) {
                    send(
                            exchange,
                            200,
                            files.isEmpty()
                                    ? "[]"
                                    : "[{\"id\":7,\"name\":\"app\",\"version\":\"1.0.0\",\"package_type\":\"generic\"}]");
                } else if (raw.equals(PROJECT + "/packages/7/package_files") && method.equals("GET")) {
                    List<Object> out = new ArrayList<>();
                    long id = 1;
                    for (Map.Entry<String, byte[]> f : files.entrySet()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", id++);
                        m.put("file_name", f.getKey());
                        m.put("size", (long) f.getValue().length);
                        m.put("file_sha256", Hashing.sha256Hex(f.getValue()));
                        out.add(m);
                    }
                    send(exchange, 200, MiniJson.write(out));
                } else if (raw.startsWith(PROJECT + "/packages/generic/app/1.0.0/") && method.equals("PUT")) {
                    String name = URLDecoder.decode(
                            raw.substring((PROJECT + "/packages/generic/app/1.0.0/").length()), StandardCharsets.UTF_8);
                    writes.incrementAndGet();
                    files.put(name, body);
                    send(exchange, 201, "{\"message\":\"201 Created\"}");
                } else if (raw.equals(PROJECT + "/releases/v1.0.0") && method.equals("GET")) {
                    Map<String, String> made = links;
                    if (made == null) {
                        send(exchange, 404, "{\"message\":\"404 Not Found\"}");
                    } else {
                        List<Object> out = new ArrayList<>();
                        made.forEach((name, url) -> out.add(Map.of("name", name, "url", url)));
                        send(
                                exchange,
                                200,
                                MiniJson.write(Map.of("tag_name", "v1.0.0", "assets", Map.of("links", out))));
                    }
                } else if (raw.equals(PROJECT + "/releases") && method.equals("POST")) {
                    if (links != null) {
                        send(exchange, 409, "{\"message\":\"Release already exists\"}");
                        return;
                    }
                    writes.incrementAndGet();
                    Object req = MiniJson.parse(new String(body, StandardCharsets.UTF_8));
                    Map<String, String> made = new LinkedHashMap<>();
                    for (Object l : MiniJson.list(MiniJson.get(req, "assets"), "links")) {
                        made.put(String.valueOf(MiniJson.str(l, "name")), String.valueOf(MiniJson.str(l, "url")));
                    }
                    links = made;
                    send(exchange, 201, "{\"tag_name\":\"v1.0.0\"}");
                } else {
                    send(exchange, 404, "{\"message\":\"404 Not Found\"}");
                }
            }
        }
    }

    private static void send(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
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

    /** A tagged application at 1.0.0 whose origin is {@code origin}, its tree committed and clean. */
    private static void app(Path dir, String origin, String release) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [application]
                main     = "app.Main"
                assembly = true

                [jlink]
                aot-cache = false

                [publish]
                release = "gitlab"
                sign    = false
                sbom    = false
                """ + release);
        Path main = dir.resolve("src/main/java/app/Main.java");
        Files.createDirectories(main.getParent());
        Files.writeString(main, """
                package app;

                public class Main {
                    public static void main(String[] args) {
                        System.out.println("hello");
                    }
                }
                """);
        Files.writeString(dir.resolve(".gitignore"), "target/\n.jk/\n");
        assertThat(run("lock", "-C", dir.toString())).isZero();
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "Ada");
        git(dir, "config", "user.email", "ada@example.com");
        git(dir, "config", "commit.gpgsign", "false");
        git(dir, "config", "tag.gpgsign", "false");
        git(dir, "remote", "add", "origin", origin);
        git(dir, "add", "-A");
        git(dir, "commit", "-q", "-m", "Start");
        git(dir, "tag", "v1.0.0");
    }

    private String origin() {
        return web() + "/acme/tools/app.git";
    }

    @Test
    void a_stage_run_fills_the_package_and_the_final_run_creates_the_release_once(@TempDir Path dir) throws Exception {
        app(dir, origin(), "");
        String image = "app-1.0.0-" + HOST + (ReleasePlatform.windows(HOST) ? ".zip" : ".tar.gz");

        assertThat(run("publish", "-C", dir.toString(), "--stage", "-S")).isEqualTo(0);
        assertThat(files).containsOnlyKeys(image);
        assertThat(links).as("no release before the final run").isNull();

        assertThat(run("publish", "-C", dir.toString(), "-S")).isEqualTo(0);
        assertThat(files).containsOnlyKeys(image, "app-1.0.0-all.jar", "SHA256SUMS", "index.json");
        assertThat(links).containsOnlyKeys(image, "app-1.0.0-all.jar", "SHA256SUMS", "index.json");
        String packages = web() + PROJECT + "/packages/generic/app/1.0.0/";
        assertThat(links).containsEntry("app-1.0.0-all.jar", packages + "app-1.0.0-all.jar");
        assertThat(new String(files.get("SHA256SUMS"), StandardCharsets.UTF_8))
                .contains("  app-1.0.0-all.jar\n")
                .contains(Hashing.sha256Hex(files.get(image)) + "  " + image);
        assertThat(new String(files.get("index.json"), StandardCharsets.UTF_8))
                .contains("\"tag\":\"v1.0.0\"")
                .contains("\"url\":\"" + packages + "app-1.0.0-all.jar\"");
        assertThat(Files.readString(dir.resolve("target/jk-results.md")))
                .contains(packages)
                .contains("published v1.0.0");

        int before = writes.get();
        assertThat(run("publish", "-C", dir.toString(), "-S"))
                .as("a re-run of a published release")
                .isEqualTo(0);
        assertThat(writes).as("nothing is sent twice").hasValue(before);
    }

    @Test
    void a_final_run_that_lacks_a_platform_is_refused_before_it_builds(@TempDir Path dir) throws Exception {
        String other = HOST.equals("linux-x86_64") ? "macos-aarch64" : "linux-x86_64";
        app(dir, origin(), "\n[release]\nplatforms = [\"" + HOST + "\", \"" + other + "\"]\n");

        Capture.Streams said = Capture.both(
                () -> assertThat(run("publish", "-C", dir.toString(), "-S")).isNotZero());
        assertThat(said.out() + said.err()).contains("release v1.0.0 lacks " + other);
        assertThat(writes).hasValue(0);
        assertThat(dir.resolve("target/app-1.0.0-all.jar")).doesNotExist();
    }

    @Test
    void an_origin_on_another_forge_is_refused(@TempDir Path dir) throws Exception {
        app(dir, "https://github.com/acme/app.git", "");

        Capture.Streams said = Capture.both(
                () -> assertThat(run("publish", "-C", dir.toString(), "-S")).isNotZero());
        assertThat(said.out() + said.err()).contains("origin https://github.com/acme/app.git is not a GitLab remote");
        assertThat(writes).hasValue(0);
    }

    @Test
    void a_token_without_the_scope_is_refused_naming_it(@TempDir Path dir) throws Exception {
        app(dir, origin(), "");
        refuse = 403;

        Capture.Streams said = Capture.both(
                () -> assertThat(run("publish", "-C", dir.toString(), "-S")).isNotZero());
        assertThat(said.out() + said.err())
                .contains("GitLab refused the release; the token needs api scope and Developer")
                .doesNotContain(TOKEN);
        assertThat(dir.resolve("target/app-1.0.0-all.jar")).doesNotExist();
    }

    @Test
    void a_dry_run_plans_the_release_and_sends_nothing(@TempDir Path dir) throws Exception {
        app(dir, origin(), "");

        String out = Capture.stdout(() -> assertThat(run("publish", "-C", dir.toString(), "--dry-run", "-S"))
                .isZero());
        assertThat(out)
                .contains("would create the release v1.0.0")
                .contains("app-1.0.0-all.jar")
                .doesNotContain(TOKEN);
        assertThat(files).isEmpty();
        assertThat(writes).hasValue(0);
    }
}
