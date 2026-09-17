// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.util.JkDirs;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Maven Central's per-IP quota is sticky, and jk's own request rate is not what trips it — a
 * single-dependency project is refused as readily as a large one. The only recovery is to ask
 * elsewhere, wholesale, for a window.
 */
class CentralMirrorTest {

    private static CentralMirror mirror(Path dir) {
        return new CentralMirror(dir, Duration.ofHours(4), true);
    }

    private static final URI CENTRAL =
            URI.create("https://repo.maven.apache.org/maven2/org/tomlj/tomlj/maven-metadata.xml");

    @Test
    void nothing_is_rerouted_until_a_429_is_seen(@TempDir Path dir) {
        CentralMirror m = mirror(dir);
        assertThat(m.active()).isFalse();
        assertThat(m.route(CENTRAL)).isEqualTo(CENTRAL);
    }

    @Test
    void a_429_opens_the_window_and_reroutes_central(@TempDir Path dir) {
        CentralMirror m = mirror(dir);
        m.noteRateLimited();

        assertThat(m.active()).isTrue();
        assertThat(m.route(CENTRAL))
                .hasToString("https://maven-central.storage-download.googleapis.com/maven2/"
                        + "org/tomlj/tomlj/maven-metadata.xml");
    }

    @Test
    void the_mirror_keeps_the_maven2_prefix_not_the_2019_snapshot_path(@TempDir Path dir) {
        // /repos/central/data/ also answers 200 but serves a 2019 snapshot; landing there would
        // resolve floating selectors to years-old versions instead of failing.
        CentralMirror m = mirror(dir);
        m.noteRateLimited();
        assertThat(m.route(CENTRAL).toString()).contains("/maven2/").doesNotContain("/repos/central/data/");
    }

    @Test
    void only_central_is_rerouted(@TempDir Path dir) {
        CentralMirror m = mirror(dir);
        m.noteRateLimited();

        for (String other : new String[] {
            "https://dl.google.com/dl/android/maven2/androidx/core/core/maven-metadata.xml",
            "https://nexus.internal.example/repo/org/foo/bar/maven-metadata.xml",
            "https://maven-central.storage-download.googleapis.com/maven2/org/foo/maven-metadata.xml"
        }) {
            assertThat(m.route(URI.create(other))).hasToString(other);
        }
    }

    @Test
    void the_window_expires_on_the_stamps_mtime(@TempDir Path dir) throws Exception {
        CentralMirror m = new CentralMirror(dir, Duration.ofHours(4), true);
        m.noteRateLimited();
        assertThat(m.active()).isTrue();

        // Age the stamp past the window — mtime is the clock, so this needs no sleeping.
        Path stamp = Files.list(dir).findFirst().orElseThrow();
        Files.setLastModifiedTime(stamp, FileTime.from(Instant.now().minus(Duration.ofHours(5))));

        assertThat(m.active()).isFalse();
        assertThat(m.route(CENTRAL)).isEqualTo(CENTRAL);
    }

    @Test
    void the_window_survives_a_new_instance(@TempDir Path dir) {
        // An engine restart between attempts must not forget the rate limit and go back to Central.
        mirror(dir).noteRateLimited();
        assertThat(mirror(dir).active()).isTrue();
    }

    @Test
    void a_later_429_extends_the_window(@TempDir Path dir) throws Exception {
        CentralMirror m = mirror(dir);
        m.noteRateLimited();
        Path stamp = Files.list(dir).findFirst().orElseThrow();
        Files.setLastModifiedTime(stamp, FileTime.from(Instant.now().minus(Duration.ofHours(3))));
        Instant before = m.activeUntil();

        m.noteRateLimited(); // still rate-limited ⇒ the quota is still in force

        assertThat(m.activeUntil()).isAfter(before);
    }

    @Test
    void it_can_be_switched_off(@TempDir Path dir) {
        CentralMirror off = new CentralMirror(dir, Duration.ofHours(4), false);
        off.noteRateLimited();
        assertThat(off.active()).isFalse();
        assertThat(off.route(CENTRAL)).isEqualTo(CENTRAL);
    }

    @Test
    void the_window_has_exactly_one_owner_on_one_directory() {
        // Both legs of a build act on this window, so two instances on two directories would leave the
        // wholesale switch sticky for only one of them. One instance, one stamp, one fact.
        assertThat(CentralMirror.standard()).isSameAs(CentralMirror.standard());
        assertThat(CentralMirror.standard().stampFile())
                .isEqualTo(JkDirs.cache().resolve("central-rate-limited.stamp"));
    }

    @Test
    void the_env_switch_accepts_the_usual_spellings() {
        assertThat(CentralMirror.enabledByEnv(n -> null)).isTrue();
        assertThat(CentralMirror.enabledByEnv(n -> "")).isTrue();
        assertThat(CentralMirror.enabledByEnv(n -> "on")).isTrue();
        for (String off : new String[] {"off", "OFF", "false", "0", "no"}) {
            assertThat(CentralMirror.enabledByEnv(n -> off)).as(off).isFalse();
        }
    }

    // ---- the transport behaviour, against a local server: no network -----------

    /**
     * Two contexts on one loopback server stand in for Central and the mirror, so the failover is
     * exercised end to end without touching the real hosts — which, given the whole point is a rate
     * limit, would be a poor way to test it.
     */
    @Test
    void a_central_429_is_reissued_against_the_mirror_in_the_same_call(@TempDir Path dir) throws Exception {
        var hits = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/maven2/", ex -> {
            hits.add("central" + ex.getRequestURI().getPath());
            byte[] b = "rate limited".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(429, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/mirror/", ex -> {
            hits.add("mirror" + ex.getRequestURI().getPath());
            byte[] b = "<metadata/>".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            CentralMirror m = new CentralMirror(
                    dir, Duration.ofHours(4), true, "127.0.0.1", "http://127.0.0.1:" + port + "/mirror");
            Http http = new Http(
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .build(),
                    new Duration[] {Duration.ofMillis(1)},
                    m);

            var response = http.get(
                    URI.create("http://127.0.0.1:" + port + "/maven2/org/tomlj/tomlj/maven-metadata.xml"), Map.of());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("<metadata/>");
            // Central asked once, then the mirror answered — the resolve completes rather than failing.
            assertThat(hits)
                    .containsExactly(
                            "central/maven2/org/tomlj/tomlj/maven-metadata.xml",
                            "mirror/mirror/org/tomlj/tomlj/maven-metadata.xml");
            assertThat(m.active()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    /**
     * A streamed 429 has a body nobody will read. The reissue to the mirror drains it first, as a
     * retried 5xx is drained: the server here runs its handlers on one thread and answers with a
     * body far larger than the socket buffers, so an undrained refusal leaves that thread blocked
     * in its write and the mirror request is never served.
     */
    @Test
    void a_streamed_central_429_is_drained_before_the_mirror_is_asked(@TempDir Path dir) throws Exception {
        byte[] big = new byte[64 << 20];
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/maven2/", ex -> {
            ex.sendResponseHeaders(429, big.length);
            ex.getResponseBody().write(big);
            ex.close();
        });
        server.createContext("/mirror/", ex -> {
            byte[] b = "jar-bytes".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            CentralMirror m = new CentralMirror(
                    dir, Duration.ofHours(4), true, "127.0.0.1", "http://127.0.0.1:" + port + "/mirror");
            Http http = new Http(
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .build(),
                    new Duration[] {Duration.ofMillis(1)},
                    m);
            URI uri = URI.create("http://127.0.0.1:" + port + "/maven2/org/tomlj/tomlj/1.0/tomlj-1.0.jar");

            var response = CompletableFuture.supplyAsync(() -> {
                        try {
                            return http.getStream(uri, Map.of());
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .get(20, TimeUnit.SECONDS);

            assertThat(response.statusCode()).isEqualTo(200);
            try (var body = response.body()) {
                assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8))
                        .isEqualTo("jar-bytes");
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void once_the_window_is_open_central_is_never_asked_again(@TempDir Path dir) throws Exception {
        // One 429 should cost one request to Central, not one per call.
        var hits = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/maven2/", ex -> {
            hits.add("central");
            ex.sendResponseHeaders(429, -1);
            ex.close();
        });
        server.createContext("/mirror/", ex -> {
            hits.add("mirror");
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            CentralMirror m = new CentralMirror(
                    dir, Duration.ofHours(4), true, "127.0.0.1", "http://127.0.0.1:" + port + "/mirror");
            m.noteRateLimited(); // window already open, e.g. from an earlier build
            Http http = new Http(
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .build(),
                    new Duration[] {Duration.ofMillis(1)},
                    m);

            URI u = URI.create("http://127.0.0.1:" + port + "/maven2/org/foo/bar/maven-metadata.xml");
            http.get(u, Map.of());
            http.get(u, Map.of());

            assertThat(hits).containsExactly("mirror", "mirror");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void a_non_429_central_response_opens_no_window(@TempDir Path dir) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/maven2/", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            CentralMirror m = new CentralMirror(
                    dir, Duration.ofHours(4), true, "127.0.0.1", "http://127.0.0.1:" + port + "/mirror");
            Http http = new Http(
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .build(),
                    new Duration[] {Duration.ofMillis(1)},
                    m);

            assertThat(http.get(URI.create("http://127.0.0.1:" + port + "/maven2/a/b/maven-metadata.xml"), Map.of())
                            .statusCode())
                    .isEqualTo(404);
            assertThat(m.active()).isFalse(); // a genuine miss is not a rate limit
        } finally {
            server.stop(0);
        }
    }

    // ---- a Cloudflare block: Central's other way of refusing a host ---------------

    /**
     * Central fronts its edge with Cloudflare, and a host it judges abusive is answered 403 with
     * {@code Server: cloudflare} on every request while curl from the next machine gets 200. A 403
     * without those headers is a plain permission answer and is handed back as one.
     */
    @Test
    void a_central_403_from_cloudflare_is_reissued_against_the_mirror_and_the_note_names_the_block(@TempDir Path dir)
            throws Exception {
        var hits = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/maven2/", ex -> {
            hits.add("central");
            byte[] b = "This IP has been blocked".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Server", "cloudflare");
            ex.getResponseHeaders().set("cf-ray", "a3c709d5db0ea68c-MIA");
            ex.sendResponseHeaders(403, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/mirror/", ex -> {
            hits.add("mirror");
            byte[] b = "<metadata/>".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            CentralMirror m = new CentralMirror(
                    dir, Duration.ofHours(4), true, "127.0.0.1", "http://127.0.0.1:" + port + "/mirror");
            Http http = new Http(
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .build(),
                    new Duration[] {Duration.ofMillis(1)},
                    m);

            var response = http.get(
                    URI.create("http://127.0.0.1:" + port
                            + "/maven2/org/junit/platform/junit-platform-launcher/maven-metadata.xml"),
                    Map.of());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(hits).containsExactly("central", "mirror");
            assertThat(m.active()).isTrue();
            assertThat(m.note()).contains("Maven Central is blocking this host (Cloudflare); using the mirror for 4 h");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void a_403_without_cloudflares_headers_is_a_plain_answer_and_opens_no_window(@TempDir Path dir) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/maven2/", ex -> {
            ex.sendResponseHeaders(403, -1);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            CentralMirror m = new CentralMirror(
                    dir, Duration.ofHours(4), true, "127.0.0.1", "http://127.0.0.1:" + port + "/mirror");
            Http http = new Http(
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .build(),
                    new Duration[] {Duration.ofMillis(1)},
                    m);

            assertThat(http.get(URI.create("http://127.0.0.1:" + port + "/maven2/a/b/maven-metadata.xml"), Map.of())
                            .statusCode())
                    .isEqualTo(403);
            assertThat(m.active()).isFalse();
            assertThat(m.note()).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void the_block_is_recognised_by_either_cloudflare_header() {
        assertThat(CentralMirror.isCloudflareBlock(403, headers("Server", "cloudflare")))
                .isTrue();
        assertThat(CentralMirror.isCloudflareBlock(403, headers("server", "Cloudflare")))
                .isTrue();
        assertThat(CentralMirror.isCloudflareBlock(403, headers("cf-mitigated", "challenge")))
                .isTrue();
        assertThat(CentralMirror.isCloudflareBlock(403, headers("Server", "nginx")))
                .isFalse();
        assertThat(CentralMirror.isCloudflareBlock(403, headers())).isFalse();
        // Cloudflare in front of a healthy origin passes its header through on every answer.
        assertThat(CentralMirror.isCloudflareBlock(200, headers("Server", "cloudflare")))
                .isFalse();
        assertThat(CentralMirror.isCloudflareBlock(429, headers("Server", "cloudflare")))
                .isFalse();
    }

    @Test
    void the_note_names_the_cause_that_opened_the_window(@TempDir Path dir) throws Exception {
        CentralMirror m = mirror(dir);
        assertThat(m.note()).isEmpty();

        m.noteRateLimited();
        assertThat(m.note()).contains("Maven Central is rate-limiting this host (HTTP 429); using the mirror for 4 h");

        m.noteBlocked();
        assertThat(m.note()).contains("Maven Central is blocking this host (Cloudflare); using the mirror for 4 h");

        // The window is armed by hand with an empty file: the rate-limit wording is the default.
        Files.writeString(m.stampFile(), "");
        assertThat(m.note()).hasValueSatisfying(n -> assertThat(n).contains("rate-limiting this host (HTTP 429)"));
    }

    @Test
    void a_block_extends_a_window_a_rate_limit_opened(@TempDir Path dir) throws Exception {
        CentralMirror m = mirror(dir);
        m.noteRateLimited();
        Files.setLastModifiedTime(m.stampFile(), FileTime.from(Instant.now().minus(Duration.ofHours(3))));
        Instant before = m.activeUntil();

        m.noteBlocked();

        assertThat(m.activeUntil()).isAfter(before);
    }

    private static HttpHeaders headers(String... nameValue) {
        var map = new LinkedHashMap<String, List<String>>();
        for (int i = 0; i < nameValue.length; i += 2) map.put(nameValue[i], List.of(nameValue[i + 1]));
        return HttpHeaders.of(map, (a, b) -> true);
    }
}
