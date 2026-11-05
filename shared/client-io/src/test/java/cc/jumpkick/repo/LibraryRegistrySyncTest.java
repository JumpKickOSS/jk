// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Log;
import cc.jumpkick.http.Http;
import cc.jumpkick.library.LibraryCatalog;
import cc.jumpkick.testing.FakeClock;
import cc.jumpkick.util.AttemptWindow;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryRegistrySyncTest {

    private static final String ETAG = "\"sync-v1\"";
    private static final byte[] BODY = "[libraries]\nfoo = \"com.acme:foo\"\n".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private URI uri;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        hits.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/libraries.toml", exchange -> {
            hits.incrementAndGet();
            String inm = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (ETAG.equals(inm)) {
                exchange.sendResponseHeaders(304, -1);
            } else {
                exchange.getResponseHeaders().set("ETag", ETAG);
                exchange.sendResponseHeaders(200, BODY.length);
                exchange.getResponseBody().write(BODY);
            }
            exchange.close();
        });
        server.createContext("/down.toml", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/libraries.toml");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void a_failed_fetch_leaves_the_source_alone_until_the_retry_window_passes(@TempDir Path tmp) {
        URI down = uri.resolve("/down.toml");
        Path cache = tmp.resolve("libs.global.toml");
        AttemptWindow failed = new AttemptWindow(LibraryRegistrySync.RETRY_AFTER);
        FakeClock clock = new FakeClock();

        int[] afterFirst = {0};
        String log = logged(() -> {
            LibraryRegistrySync.ensurePresent(false, down, new Http(), cache, failed, clock);
            afterFirst[0] = hits.get();
            for (int lock = 0; lock < 15; lock++) {
                LibraryRegistrySync.ensurePresent(false, down, new Http(), cache, failed, clock);
                clock.advance(Duration.ofSeconds(30));
            }
        });
        assertThat(afterFirst[0]).as("the first lock asks").isPositive();
        assertThat(hits.get()).as("the locks inside the window").isEqualTo(afterFirst[0]);
        assertThat(cache).doesNotExist();
        assertThat(log.lines().filter(line -> line.contains("library registry not fetched")))
                .as("one notice for the window")
                .hasSize(1);

        clock.advance(LibraryRegistrySync.RETRY_AFTER);
        LibraryRegistrySync.ensurePresent(false, down, new Http(), cache, failed, clock);
        assertThat(hits.get()).as("the first lock after the window").isGreaterThan(afterFirst[0]);

        LibraryRegistrySync.ensurePresent(false, uri, new Http(), cache, failed, clock);
        assertThat(cache).as("another source is not held back").exists();
    }

    /** What {@code body} writes to the log at info and above. */
    private static String logged(Runnable body) {
        var out = new ByteArrayOutputStream();
        Log.install(
                new PrintStream(out, true, StandardCharsets.UTF_8), System.Logger.Level.INFO, UnaryOperator.identity());
        try {
            body.run();
        } finally {
            Log.install(System.err, System.Logger.Level.INFO, UnaryOperator.identity());
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void missing_file_is_downloaded(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("libs.global.toml");
        LibraryRegistrySync.ensurePresent(false, uri, new Http(), cache);
        assertThat(cache).exists();
        assertThat(Files.readAllBytes(cache)).isEqualTo(BODY);
        assertThat(Files.readString(LibraryCatalog.etagFileFor(cache))).isEqualTo(ETAG);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void fresh_file_skips_the_network_entirely(@TempDir Path tmp) throws Exception {
        // The resident engine keeps the file warm on the same cadence; a foreground command
        // must not stack a blocking fetch on top of a recently-downloaded registry.
        Path cache = tmp.resolve("libs.global.toml");
        Files.write(cache, BODY);
        Files.writeString(LibraryCatalog.etagFileFor(cache), ETAG);
        LibraryRegistrySync.ensurePresent(false, uri, new Http(), cache);
        assertThat(hits.get()).isZero();
        assertThat(Files.readAllBytes(cache)).isEqualTo(BODY);
    }

    @Test
    void stale_file_revalidates_with_etag_and_rearms_freshness(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("libs.global.toml");
        Files.write(cache, BODY);
        Files.writeString(LibraryCatalog.etagFileFor(cache), ETAG);
        Instant stale = Instant.now().minus(LibraryRegistrySync.FRESH_FOR).minusSeconds(60);
        Files.setLastModifiedTime(cache, FileTime.from(stale));
        LibraryRegistrySync.ensurePresent(false, uri, new Http(), cache);
        assertThat(hits.get()).isEqualTo(1);
        assertThat(Files.readAllBytes(cache)).isEqualTo(BODY);
        // The 304 re-armed the window: an immediate second call stays off the network.
        LibraryRegistrySync.ensurePresent(false, uri, new Http(), cache);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void missing_file_with_orphan_etag_is_still_downloaded(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("libs.global.toml");
        // Cache deleted (user refetch / partial prune) but the sidecar survived: the server
        // would answer 304 to a conditional GET, which must not leave the file absent forever.
        Files.writeString(LibraryCatalog.etagFileFor(cache), ETAG);
        LibraryRegistrySync.ensurePresent(false, uri, new Http(), cache);
        assertThat(cache).exists();
        assertThat(Files.readAllBytes(cache)).isEqualTo(BODY);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void offline_never_hits_the_network(@TempDir Path tmp) {
        Path cache = tmp.resolve("libs.global.toml");
        LibraryRegistrySync.ensurePresent(true, uri, new Http(), cache);
        assertThat(hits.get()).isZero();
        assertThat(cache).doesNotExist();
    }
}
