// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.ReleasePlatform;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: the release lane against a loopback S3 bucket. A single-platform application stages
 * its runtime image, then the final run uploads the fat jar, writes SHA256SUMS and index.json and
 * points latest.json at the release, and a second final run changes nothing. A final run that
 * lacks a listed platform is refused before anything builds or uploads.
 */
@Tag("integration")
class ReleaseLanePublishTest {

    private static final String HOST = ReleasePlatform.current();
    private static final String[] ENV = {"AWS_ENDPOINT_URL", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_REGION"
    };

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        System.setProperty(
                "jk.env.AWS_ENDPOINT_URL",
                "http://127.0.0.1:" + server.getAddress().getPort());
        System.setProperty("jk.env.AWS_ACCESS_KEY_ID", "test-key");
        System.setProperty("jk.env.AWS_SECRET_ACCESS_KEY", "test-secret");
        System.setProperty("jk.env.AWS_REGION", "us-east-1");
    }

    @AfterEach
    void stop() {
        server.stop(0);
        for (String name : ENV) System.clearProperty("jk.env." + name);
    }

    /** create-only on {@code If-None-Match: *}, compare-and-swap on {@code If-Match}, as S3 does. */
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod())) {
                byte[] body = objects.get(path);
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().set("ETag", etag(body));
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            String match = exchange.getRequestHeaders().getFirst("If-Match");
            synchronized (objects) {
                byte[] previous = objects.get(path);
                boolean ok = match == null ? previous == null : previous != null && match.equals(etag(previous));
                if (!ok) {
                    exchange.sendResponseHeaders(412, -1);
                    return;
                }
                objects.put(path, body);
                exchange.sendResponseHeaders(200, -1);
            }
        }
    }

    private static String etag(byte[] body) {
        return "\"" + Integer.toHexString(Arrays.hashCode(body)) + "\"";
    }

    private String text(String key) {
        byte[] body = objects.get("/releases/app/" + key);
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }

    private static void app(Path dir, String release) throws IOException {
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
                release = "s3://releases/app/"
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
    }

    @Test
    void a_stage_run_then_the_final_run_publish_the_release_once(@TempDir Path dir) throws Exception {
        app(dir, "");
        String image = "app-1.0.0-" + HOST + (ReleasePlatform.windows(HOST) ? ".zip" : ".tar.gz");

        assertThat(run("publish", "-C", dir.toString(), "--stage", "-S")).isEqualTo(0);
        assertThat(objects.keySet()).containsExactly("/releases/app/1.0.0/" + image);
        assertThat(text("latest.json")).isNull();

        assertThat(run("publish", "-C", dir.toString(), "-S")).isEqualTo(0);
        assertThat(objects.keySet())
                .containsExactlyInAnyOrder(
                        "/releases/app/1.0.0/" + image,
                        "/releases/app/1.0.0/app-1.0.0-all.jar",
                        "/releases/app/1.0.0/SHA256SUMS",
                        "/releases/app/1.0.0/index.json",
                        "/releases/app/latest.json");
        assertThat(text("1.0.0/SHA256SUMS")).contains("  app-1.0.0-all.jar\n").contains("  " + image + "\n");
        assertThat(text("1.0.0/index.json")).contains("\"version\":\"1.0.0\"").contains("\"tag\":\"v1.0.0\"");
        assertThat(text("latest.json")).isEqualTo("{\"version\":\"1.0.0\",\"index\":\"1.0.0/index.json\"}\n");
        assertThat(Files.readString(dir.resolve("target/jk-results.md")))
                .contains("| release | s3://releases/app/1.0.0/ |");

        Map<String, byte[]> before = Map.copyOf(objects);
        assertThat(run("publish", "-C", dir.toString(), "-S"))
                .as("a re-run of the final run")
                .isEqualTo(0);
        assertThat(objects).containsOnlyKeys(before.keySet());
        for (String key : before.keySet()) assertThat(objects.get(key)).as(key).isEqualTo(before.get(key));
    }

    @Test
    void a_final_run_that_lacks_a_platform_is_refused_before_it_builds(@TempDir Path dir) throws Exception {
        String other = HOST.equals("linux-x86_64") ? "macos-aarch64" : "linux-x86_64";
        app(dir, "\n[release]\nplatforms = [\"" + HOST + "\", \"" + other + "\"]\n");

        assertThat(run("publish", "-C", dir.toString(), "-S")).isNotZero();
        assertThat(objects).isEmpty();
        assertThat(dir.resolve("target/app-1.0.0-all.jar")).doesNotExist();
    }

    @Test
    void a_dry_run_plans_the_release_and_sends_nothing(@TempDir Path dir) throws Exception {
        app(dir, "");

        assertThat(run("publish", "-C", dir.toString(), "--dry-run", "-S")).isEqualTo(0);
        assertThat(objects).isEmpty();
        assertThat(Files.readString(dir.resolve("target/jk-results.md"))).contains("| release |");
    }
}
