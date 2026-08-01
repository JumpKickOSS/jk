// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.library.LibraryCatalog;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryRegistrySyncTest {

    private static final String ETAG = "\"sync-v1\"";
    private static final byte[] BODY =
            "[libraries]\nfoo = \"com.acme:foo\"\n".getBytes(StandardCharsets.UTF_8);

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
        server.start();
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/libraries.toml");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void missing_file_is_downloaded(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("libs.global.toml");
        LibraryRegistrySync.ensurePresent(false, uri, cache);
        assertThat(cache).exists();
        assertThat(Files.readAllBytes(cache)).isEqualTo(BODY);
        assertThat(Files.readString(LibraryCatalog.etagFileFor(cache))).isEqualTo(ETAG);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void present_file_revalidates_with_etag(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("libs.global.toml");
        Files.write(cache, BODY);
        Files.writeString(LibraryCatalog.etagFileFor(cache), ETAG);
        LibraryRegistrySync.ensurePresent(false, uri, cache);
        assertThat(hits.get()).isEqualTo(1);
        assertThat(Files.readAllBytes(cache)).isEqualTo(BODY);
    }

    @Test
    void offline_never_hits_the_network(@TempDir Path tmp) {
        Path cache = tmp.resolve("libs.global.toml");
        LibraryRegistrySync.ensurePresent(true, uri, cache);
        assertThat(hits.get()).isZero();
        assertThat(cache).doesNotExist();
    }
}
