// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1277: Maven Central's per-IP quota is sticky, and jk's own request rate is not what trips it — a
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
        var hits = new java.util.concurrent.CopyOnWriteArrayList<String>();
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
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
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

    @Test
    void once_the_window_is_open_central_is_never_asked_again(@TempDir Path dir) throws Exception {
        // One 429 should cost one request to Central, not one per call.
        var hits = new java.util.concurrent.CopyOnWriteArrayList<String>();
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
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
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
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
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
}
