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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: the release lane against a loopback Forgejo API. A tagged single-platform application
 * whose origin is on a self-hosted Forgejo stages its runtime image into a new draft release; the
 * final run uploads the fat jar, SHA256SUMS and index.json and publishes the draft; a second final
 * run changes nothing. A final run that lacks a listed platform is refused before anything builds,
 * and so are an origin on another forge and a token without the scope.
 */
@Tag("integration")
class ReleaseForgejoPublishTest {

    private static final String HOST = ReleasePlatform.current();
    private static final String TOKEN = "e2e-forgejo-release-token";
    private static final String REPOS = "/api/v1/repos/acme/app";
    private static final String[] ENV = {"JK_GITEA_TOKEN"};

    /** One release of the fake: whether it is a draft, and its attachments' bytes by name. */
    private static final class Release {
        final long id;
        final String tag;
        volatile boolean draft;
        final Map<String, byte[]> assets = new ConcurrentHashMap<>();

        Release(long id, String tag, boolean draft) {
            this.id = id;
            this.tag = tag;
            this.draft = draft;
        }
    }

    private final List<Release> releases = new ArrayList<>();
    private final AtomicInteger writes = new AtomicInteger();
    private final AtomicLong ids = new AtomicLong(1);
    private volatile int refuse;
    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        System.setProperty("jk.env.JK_GITEA_TOKEN", TOKEN);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        for (String name : ENV) System.clearProperty("jk.env." + name);
    }

    private String web() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * Releases listed with drafts, created, patched; attachments listed, uploaded as a raw body and
     * read by UUID, as Forgejo's API does.
     */
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (refuse != 0) {
                send(exchange, refuse, "{\"message\":\"token does not have at least one of required scope(s)\"}");
                return;
            }
            if (!("token " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                send(exchange, 401, "{}");
                return;
            }
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String[] p = path.split("/");
            byte[] body = exchange.getRequestBody().readAllBytes();
            synchronized (releases) {
                if (path.startsWith("/attachments/")) {
                    byte[] bytes = null;
                    for (Release r : releases) {
                        for (Map.Entry<String, byte[]> a : r.assets.entrySet()) {
                            if (uuid(r, a.getKey()).equals(p[2])) bytes = a.getValue();
                        }
                    }
                    if (bytes == null) {
                        send(exchange, 404, "{}");
                    } else {
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                } else if (path.equals(REPOS + "/releases") && method.equals("GET")) {
                    List<Object> out = new ArrayList<>();
                    for (Release r : releases) out.add(json(r));
                    send(exchange, 200, MiniJson.write(out));
                } else if (path.equals(REPOS + "/releases") && method.equals("POST")) {
                    writes.incrementAndGet();
                    Object req = MiniJson.parse(new String(body, StandardCharsets.UTF_8));
                    Release r = new Release(
                            ids.incrementAndGet(),
                            String.valueOf(MiniJson.str(req, "tag_name")),
                            Boolean.TRUE.equals(MiniJson.get(req, "draft")));
                    releases.add(r);
                    send(exchange, 201, MiniJson.write(json(r)));
                } else if (path.matches(REPOS + "/releases/\\d+/assets") && method.equals("GET")) {
                    List<Object> out = new ArrayList<>();
                    Release r = byId(Long.parseLong(p[7]));
                    if (r != null) {
                        for (Map.Entry<String, byte[]> a : r.assets.entrySet()) {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("id", (long) a.getKey().hashCode());
                            m.put("name", a.getKey());
                            m.put("size", (long) a.getValue().length);
                            m.put("uuid", uuid(r, a.getKey()));
                            out.add(m);
                        }
                    }
                    send(exchange, 200, MiniJson.write(out));
                } else if (path.matches(REPOS + "/releases/\\d+/assets") && method.equals("POST")) {
                    Release r = byId(Long.parseLong(p[7]));
                    String name = URLDecoder.decode(
                            exchange.getRequestURI().getRawQuery().replaceFirst("^name=", ""), StandardCharsets.UTF_8);
                    if (r == null) {
                        send(exchange, 404, "{}");
                        return;
                    }
                    writes.incrementAndGet();
                    r.assets.put(name, body);
                    send(exchange, 201, "{}");
                } else if (path.matches(REPOS + "/releases/\\d+") && method.equals("PATCH")) {
                    writes.incrementAndGet();
                    Release r = byId(Long.parseLong(p[7]));
                    Object req = MiniJson.parse(new String(body, StandardCharsets.UTF_8));
                    if (r != null && MiniJson.get(req, "draft") instanceof Boolean draft) r.draft = draft;
                    send(exchange, r == null ? 404 : 200, r == null ? "{}" : MiniJson.write(json(r)));
                } else {
                    send(exchange, 404, "{}");
                }
            }
        }
    }

    private static String uuid(Release r, String name) {
        return Hashing.sha256Hex(r.id + "/" + name).substring(0, 32);
    }

    private static Map<String, Object> json(Release r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("tag_name", r.tag);
        m.put("draft", r.draft);
        return m;
    }

    private @Nullable Release byId(long id) {
        return releases.stream().filter(r -> r.id == id).findFirst().orElse(null);
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
                release = "forgejo"
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
        return web() + "/acme/app.git";
    }

    private byte[] asset(String name) {
        return releases.getFirst().assets.get(name);
    }

    @Test
    void a_stage_run_then_the_final_run_publish_the_draft_once(@TempDir Path dir) throws Exception {
        app(dir, origin(), "");
        String image = "app-1.0.0-" + HOST + (ReleasePlatform.windows(HOST) ? ".zip" : ".tar.gz");

        assertThat(run("publish", "-C", dir.toString(), "--stage", "-S")).isEqualTo(0);
        assertThat(releases).singleElement().satisfies(r -> {
            assertThat(r.tag).isEqualTo("v1.0.0");
            assertThat(r.draft).isTrue();
            assertThat(r.assets).containsOnlyKeys(image);
        });

        assertThat(run("publish", "-C", dir.toString(), "-S")).isEqualTo(0);
        Release release = releases.getFirst();
        assertThat(release.draft).isFalse();
        assertThat(release.assets).containsOnlyKeys(image, "app-1.0.0-all.jar", "SHA256SUMS", "index.json");
        String sums = new String(asset("SHA256SUMS"), StandardCharsets.UTF_8);
        assertThat(sums).contains("  app-1.0.0-all.jar\n").contains(Hashing.sha256Hex(asset(image)) + "  " + image);
        assertThat(new String(asset("index.json"), StandardCharsets.UTF_8))
                .contains("\"tag\":\"v1.0.0\"")
                .doesNotContain("\"url\"");
        assertThat(Files.readString(dir.resolve("target/jk-results.md")))
                .contains(web() + "/acme/app/releases/download/v1.0.0/")
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
        app(dir, "https://gitlab.com/acme/app.git", "");

        Capture.Streams said = Capture.both(
                () -> assertThat(run("publish", "-C", dir.toString(), "-S")).isNotZero());
        assertThat(said.out() + said.err()).contains("origin https://gitlab.com/acme/app.git is not a Forgejo remote");
        assertThat(writes).hasValue(0);
    }

    @Test
    void a_token_without_the_scope_is_refused_naming_it(@TempDir Path dir) throws Exception {
        app(dir, origin(), "");
        refuse = 403;

        Capture.Streams said = Capture.both(
                () -> assertThat(run("publish", "-C", dir.toString(), "-S")).isNotZero());
        assertThat(said.out() + said.err())
                .contains("Forgejo refused the release; the token needs write:repository")
                .doesNotContain(TOKEN);
        assertThat(dir.resolve("target/app-1.0.0-all.jar")).doesNotExist();
    }

    @Test
    void a_dry_run_plans_the_draft_and_sends_nothing(@TempDir Path dir) throws Exception {
        app(dir, origin(), "");

        String out = Capture.stdout(() -> assertThat(run("publish", "-C", dir.toString(), "--dry-run", "-S"))
                .isZero());
        assertThat(out).contains("would publish the draft release v1.0.0").contains("app-1.0.0-all.jar");
        assertThat(releases).isEmpty();
        assertThat(writes).hasValue(0);
    }
}
