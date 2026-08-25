// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.http.Http;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreFeedRefreshTest {

    private static final String ETAG = "\"reg-v1\"";
    private static final byte[] LIBS_BODY = "[libraries]\nfoo = \"com.acme:foo\"\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JDKS_BODY = """
            {
              "jdks": [
                {
                  "vendor": "Eclipse",
                  "product": "Temurin",
                  "default": true,
                  "jdk_version_major": 21,
                  "jdk_version": "21.0.5",
                  "suggested_sdk_name": "temurin-21",
                  "packages": [
                    {
                      "os": "linux",
                      "arch": "x86_64",
                      "version": "21.0.5",
                      "url": "https://example.invalid/temurin-21.0.5-linux-x64.tar.gz",
                      "package_type": "targz",
                      "package_to_java_home_prefix": "",
                      "install_folder_name": "temurin-21.0.5",
                      "archive_size": 2048,
                      "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
                    }
                  ]
                }
              ]
            }
            """.getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private URI libsUri;
    private URI jdkUri;
    private final AtomicInteger libHits = new AtomicInteger();
    private final AtomicInteger jdkHits = new AtomicInteger();
    private volatile String lastIfNoneMatch;

    @BeforeEach
    void startServer() throws Exception {
        libHits.set(0);
        jdkHits.set(0);
        lastIfNoneMatch = null;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/libraries.toml", exchange -> {
            libHits.incrementAndGet();
            lastIfNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (ETAG.equals(lastIfNoneMatch)) {
                exchange.sendResponseHeaders(304, -1);
            } else {
                exchange.getResponseHeaders().set("ETag", ETAG);
                exchange.sendResponseHeaders(200, LIBS_BODY.length);
                exchange.getResponseBody().write(LIBS_BODY);
            }
            exchange.close();
        });
        server.createContext("/feed/jdks.json", exchange -> {
            jdkHits.incrementAndGet();
            String ifMod = exchange.getRequestHeaders().getFirst("If-Modified-Since");
            if (ifMod != null) {
                exchange.sendResponseHeaders(304, -1);
            } else {
                exchange.sendResponseHeaders(200, JDKS_BODY.length);
                exchange.getResponseBody().write(JDKS_BODY);
            }
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        libsUri = URI.create("http://127.0.0.1:" + port + "/libraries.toml");
        jdkUri = URI.create("http://127.0.0.1:" + port + "/feed/jdks.json");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void missing_files_are_fetched_and_written(@TempDir Path tmp) throws Exception {
        Path libs = tmp.resolve("libs.global.toml");
        Path jdks = tmp.resolve("jdks.json");
        List<String> logs = new ArrayList<>();
        AtomicInteger after = new AtomicInteger();
        try (StoreFeedRefresh refresh = new StoreFeedRefresh(
                logs::add, new Http(), () -> libs, () -> jdks, libsUri, jdkUri, after::incrementAndGet)) {
            refresh.tickQuietly();
        }
        assertThat(libs).exists();
        assertThat(Files.readAllBytes(libs)).isEqualTo(LIBS_BODY);
        assertThat(Files.readString(tmp.resolve(".libs.global.toml.etag"))).isEqualTo(ETAG);
        assertThat(jdks).exists();
        assertThat(libHits.get()).isEqualTo(1);
        assertThat(jdkHits.get()).isEqualTo(1);
        assertThat(logs).anyMatch(s -> s.contains("refreshed library registry"));
        assertThat(after.get()).isEqualTo(1);
    }

    @Test
    void fresh_files_skip_the_network(@TempDir Path tmp) throws Exception {
        Path libs = tmp.resolve("libs.global.toml");
        Path jdks = tmp.resolve("jdks.json");
        Files.write(libs, LIBS_BODY);
        Files.write(jdks, JDKS_BODY);
        Files.setLastModifiedTime(libs, FileTime.from(Instant.now()));
        Files.setLastModifiedTime(jdks, FileTime.from(Instant.now()));

        AtomicInteger after = new AtomicInteger();
        try (StoreFeedRefresh refresh = new StoreFeedRefresh(
                s -> {}, new Http(), () -> libs, () -> jdks, libsUri, jdkUri, after::incrementAndGet)) {
            refresh.tickQuietly();
        }
        assertThat(libHits.get()).isZero();
        assertThat(jdkHits.get()).isZero();
        // GC enqueue still runs when catalogs are warm.
        assertThat(after.get()).isEqualTo(1);
    }

    @Test
    void stale_library_file_revalidates_with_etag(@TempDir Path tmp) throws Exception {
        Path libs = tmp.resolve("libs.global.toml");
        Path jdks = tmp.resolve("jdks.json");
        Files.write(libs, LIBS_BODY);
        Files.writeString(tmp.resolve(".libs.global.toml.etag"), ETAG);
        Files.write(jdks, JDKS_BODY);
        // Make libs stale; keep jdks fresh.
        Files.setLastModifiedTime(libs, FileTime.from(Instant.now().minus(Duration.ofHours(13))));
        Files.setLastModifiedTime(jdks, FileTime.from(Instant.now()));

        try (StoreFeedRefresh refresh =
                new StoreFeedRefresh(s -> {}, new Http(), () -> libs, () -> jdks, libsUri, jdkUri, null)) {
            refresh.tickQuietly();
        }
        assertThat(libHits.get()).isEqualTo(1);
        assertThat(lastIfNoneMatch).isEqualTo(ETAG);
        assertThat(jdkHits.get()).isZero();
        // The 304 path touches mtime so the next 12h window is quiet. The bound is 5 minutes
        // against a 12-hour window: it distinguishes "touched during this test" from "left at the
        // fixture's backdated mtime", and nothing finer. Tightening it would make it a measurement
        // of how long this test took to run (JK-2446).
        Instant mtime = Files.getLastModifiedTime(libs).toInstant();
        assertThat(Duration.between(mtime, Instant.now()))
                .as("the 304 refreshed the mtime, so the 12h quiet window restarts")
                .isLessThan(Duration.ofMinutes(5));
    }

    @Test
    void missing_library_file_with_orphan_etag_is_rewritten(@TempDir Path tmp) throws Exception {
        Path libs = tmp.resolve("libs.global.toml");
        Path jdks = tmp.resolve("jdks.json");
        Files.write(jdks, JDKS_BODY);
        Files.setLastModifiedTime(jdks, FileTime.from(Instant.now()));
        // Cache pruned but the sidecar survived: a conditional GET would 304 and the file
        // would then stay missing on every 12 h tick, forever.
        Files.writeString(tmp.resolve(".libs.global.toml.etag"), ETAG);

        try (StoreFeedRefresh refresh =
                new StoreFeedRefresh(s -> {}, new Http(), () -> libs, () -> jdks, libsUri, jdkUri, null)) {
            refresh.tickQuietly();
        }
        assertThat(libs).exists();
        assertThat(Files.readAllBytes(libs)).isEqualTo(LIBS_BODY);
        assertThat(lastIfNoneMatch).isNull(); // unconditional — nothing to be "unchanged" against
        assertThat(libHits.get()).isEqualTo(1);
    }

    @Test
    void after_tick_exceptions_do_not_escape(@TempDir Path tmp) throws Exception {
        Path libs = tmp.resolve("libs.global.toml");
        Path jdks = tmp.resolve("jdks.json");
        Files.write(libs, LIBS_BODY);
        Files.write(jdks, JDKS_BODY);
        Files.setLastModifiedTime(libs, FileTime.from(Instant.now()));
        Files.setLastModifiedTime(jdks, FileTime.from(Instant.now()));
        List<String> logs = new ArrayList<>();
        try (StoreFeedRefresh refresh =
                new StoreFeedRefresh(logs::add, new Http(), () -> libs, () -> jdks, libsUri, jdkUri, () -> {
                    throw new RuntimeException("boom");
                })) {
            refresh.tickQuietly(); // must not throw
        }
        // afterTick failures are swallowed quietly (no retries, no log spam)
        assertThat(logs).isEmpty();
    }

    @Test
    void needs_refresh_when_missing_or_old(@TempDir Path tmp) throws Exception {
        Path missing = tmp.resolve("nope");
        assertThat(StoreFeedRefresh.needsRefresh(missing, Duration.ofHours(12))).isTrue();

        Path fresh = tmp.resolve("fresh");
        Files.writeString(fresh, "x");
        Files.setLastModifiedTime(fresh, FileTime.from(Instant.now()));
        assertThat(StoreFeedRefresh.needsRefresh(fresh, Duration.ofHours(12))).isFalse();

        Path old = tmp.resolve("old");
        Files.writeString(old, "x");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofHours(12))));
        assertThat(StoreFeedRefresh.needsRefresh(old, Duration.ofHours(12))).isTrue();
    }

    @Test
    void network_failure_is_swallowed(@TempDir Path tmp) throws Exception {
        Path libs = tmp.resolve("libs.global.toml");
        Path jdks = tmp.resolve("jdks.json");
        List<String> logs = new ArrayList<>();
        URI dead = URI.create("http://127.0.0.1:1/nope");
        // failFast: connection-refused surfaces in one attempt — with the default backoff
        // ladder this single method waited out ~6s of retries, 19% of the whole unit tier.
        try (StoreFeedRefresh refresh =
                new StoreFeedRefresh(logs::add, Http.failFast(), () -> libs, () -> jdks, dead, dead, null)) {
            refresh.tickQuietly();
        }
        assertThat(libs).doesNotExist();
        // Network failures are fail-fast and quiet (no retries).
        assertThat(logs).isEmpty();
    }
}
