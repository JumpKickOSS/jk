// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.MockMavenServer;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.lock.LockfileReader;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk lock} revalidates an already-downloaded library catalog against the registry with a
 * conditional GET before resolving — the automatic counterpart to {@code jk library update}.
 */
@Tag("integration")
class LockCommandLibraryRegistryTest {

    // These tests drive the real fetch plan against a mock Maven server; fetched
    // artifacts mirror into the Maven local repo. Point that at a throwaway dir (see
    // M2Dirs) so stub artifacts never overwrite the developer's real ~/.m2 — the
    // fixture reuses real coordinates (junit-jupiter et al).
    @BeforeAll
    static void isolateM2(@TempDir Path m2) {
        System.setProperty("jk.m2.local", m2.toString());
    }

    private static final String ETAG = "\"v1\"";
    private static final byte[] FRESH_BODY = "[libraries]\nfoo = \"com.acme:foo\"\n".getBytes(StandardCharsets.UTF_8);

    @RegisterExtension
    final MockMavenServer maven = new MockMavenServer();

    private HttpServer registryServer;
    private URI registryUrl;
    private final AtomicInteger registryHits = new AtomicInteger();
    private volatile String lastIfNoneMatch;
    private volatile int registryStatus; // 0 = decide from If-None-Match; else force this status

    @BeforeEach
    void start() throws IOException {
        DefaultTestDepsFixture.seed(maven.served());

        registryHits.set(0);
        lastIfNoneMatch = null;
        registryStatus = 0;
        registryServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        registryServer.createContext("/libraries.toml", exchange -> {
            registryHits.incrementAndGet();
            lastIfNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (registryStatus != 0) {
                exchange.sendResponseHeaders(registryStatus, -1);
            } else if (ETAG.equals(lastIfNoneMatch)) {
                exchange.sendResponseHeaders(304, -1);
            } else {
                exchange.getResponseHeaders().set("ETag", ETAG);
                exchange.sendResponseHeaders(200, FRESH_BODY.length);
                exchange.getResponseBody().write(FRESH_BODY);
            }
            exchange.close();
        });
        registryServer.start();
        registryUrl =
                URI.create("http://127.0.0.1:" + registryServer.getAddress().getPort() + "/libraries.toml");
    }

    @AfterEach
    void stop() {
        registryServer.stop(0);
        cc.jumpkick.config.SessionContext.reset();
        LockfileReader.clearCache();
    }

    /** Age {@code file} past the client-side freshness window so a lock revalidates it. */
    private static void makeStale(Path file) throws IOException {
        Files.setLastModifiedTime(
                file,
                FileTime.from(Instant.now()
                        .minus(cc.jumpkick.repo.LibraryRegistrySync.FRESH_FOR)
                        .minusSeconds(60)));
    }

    @Test
    void lock_skips_a_fresh_catalog_without_touching_the_network(@TempDir Path tempDir) throws Exception {
        // The resident engine keeps the file warm on the same cadence — a lock right after a
        // download must not stack another blocking fetch on top.
        run("new", tempDir.toString());
        Path libraryCache = tempDir.resolve("libs.global.toml");
        Files.writeString(libraryCache, "[libraries]\nold = \"com.old:thing\"\n");
        int exit = lock(tempDir, libraryCache);

        assertThat(exit).isEqualTo(0);
        assertThat(registryHits.get()).isZero();
    }

    @Test
    void lock_revalidates_an_existing_catalog_and_stores_the_fresh_body_and_etag(@TempDir Path tempDir)
            throws Exception {
        run("new", tempDir.toString());
        Path libraryCache = tempDir.resolve("libs.global.toml");
        Files.writeString(libraryCache, "[libraries]\nold = \"com.old:thing\"\n");
        makeStale(libraryCache);
        int exit = lock(tempDir, libraryCache);

        assertThat(exit).isEqualTo(0);
        assertThat(registryHits.get()).isEqualTo(1);
        assertThat(lastIfNoneMatch).isNull(); // no prior ETag sidecar → unconditional first GET
        assertThat(Files.readString(libraryCache)).isEqualTo(new String(FRESH_BODY, StandardCharsets.UTF_8));
        assertThat(Files.readString(LibraryCatalog.etagFileFor(libraryCache))).isEqualTo(ETAG);
    }

    @Test
    void lock_sends_the_stored_etag_and_leaves_a_304_cache_untouched(@TempDir Path tempDir) throws Exception {
        run("new", tempDir.toString());
        Path libraryCache = tempDir.resolve("libs.global.toml");
        String original = "[libraries]\nold = \"com.old:thing\"\n";
        Files.writeString(libraryCache, original);
        Files.writeString(LibraryCatalog.etagFileFor(libraryCache), ETAG);
        makeStale(libraryCache);
        int exit = lock(tempDir, libraryCache);

        assertThat(exit).isEqualTo(0);
        assertThat(lastIfNoneMatch).isEqualTo(ETAG);
        assertThat(Files.readString(libraryCache)).isEqualTo(original); // 304 → untouched
    }

    @Test
    void lock_downloads_the_catalog_when_missing_before_parse(@TempDir Path tempDir) throws Exception {
        Path libraryCache = tempDir.resolve("libs.global.toml"); // deliberately never created

        run("new", tempDir.toString());
        int exit = lock(tempDir, libraryCache);

        assertThat(exit).isEqualTo(0);
        assertThat(registryHits.get()).isEqualTo(1);
        assertThat(libraryCache).exists();
        assertThat(Files.readString(libraryCache)).isEqualTo(new String(FRESH_BODY, StandardCharsets.UTF_8));
        assertThat(Files.readString(LibraryCatalog.etagFileFor(libraryCache))).isEqualTo(ETAG);
    }

    @Test
    void lock_offline_skips_the_registry_entirely(@TempDir Path tempDir) throws Exception {
        run("new", tempDir.toString());
        Path libraryCache = tempDir.resolve("libs.global.toml");
        Files.writeString(libraryCache, "[libraries]\nold = \"com.old:thing\"\n");
        makeStale(libraryCache);
        assertThat(lock(tempDir, libraryCache)).isEqualTo(0); // warm the jk-lock.toml
        assertThat(registryHits.get()).isEqualTo(1);

        // Stale again — only --offline (not freshness) may skip the fetch below.
        makeStale(libraryCache);
        maven.stop();
        registryServer.stop(0); // any network attempt would now fail
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        int exit;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            exit = Jk.execute(
                    "lock",
                    "--offline",
                    "-C",
                    tempDir.toString(),
                    "--cache-dir",
                    tempDir.resolve("cache").toString(),
                    "--library-registry-url",
                    registryUrl.toString(),
                    "--library-cache-file",
                    libraryCache.toString());
        } finally {
            System.setOut(originalOut);
        }

        // On failure the lock's own error text is the diagnosis — surface it (JK-2179).
        assertThat(exit)
                .as("offline lock output:\n%s", captured.toString(StandardCharsets.UTF_8))
                .isEqualTo(0);
        assertThat(registryHits.get()).isEqualTo(1); // unchanged — offline never asked
    }

    @Test
    void lock_falls_back_when_the_registry_returns_a_malformed_payload(@TempDir Path tempDir) throws Exception {
        run("new", tempDir.toString());
        Path libraryCache = tempDir.resolve("libs.global.toml");
        String original = "[libraries]\nold = \"com.old:thing\"\n";
        Files.writeString(libraryCache, original);
        makeStale(libraryCache);
        registryStatus = 200; // handled below via a body override instead
        registryServer.stop(0);
        registryServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        registryServer.createContext("/libraries.toml", exchange -> {
            registryHits.incrementAndGet();
            byte[] garbage = "not toml at all".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, garbage.length);
            exchange.getResponseBody().write(garbage);
            exchange.close();
        });
        registryServer.start();
        registryUrl =
                URI.create("http://127.0.0.1:" + registryServer.getAddress().getPort() + "/libraries.toml");

        int exit = lock(tempDir, libraryCache);

        assertThat(exit).isEqualTo(0); // malformed registry payload never fails the lock
        assertThat(Files.readString(libraryCache)).isEqualTo(original); // cache untouched
    }

    private int lock(Path tempDir, Path libraryCache) {
        return Jk.execute(
                "lock",
                "-C",
                tempDir.toString(),
                "--repo-url",
                maven.base().toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString(),
                "--library-registry-url",
                registryUrl.toString(),
                "--library-cache-file",
                libraryCache.toString());
    }
}
