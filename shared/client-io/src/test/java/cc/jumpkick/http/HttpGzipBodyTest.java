// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A response that says {@code Content-Encoding: gzip} is inflated as it is read: a body that stops
 * before its gzip header fails the read as any stalled body does, and an empty one (a 304 or a 404
 * a server labels gzip) is an empty body, answered once, not a fault that is asked again.
 */
class HttpGzipBodyTest {

    private HttpServer server;
    private URI base;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    /** Status {@code status} with {@code Content-Encoding: gzip} and no body; counts the requests. */
    private AtomicInteger emptyGzip(String path, int status) {
        AtomicInteger calls = new AtomicInteger();
        server.createContext(path, exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        return calls;
    }

    @Test
    @Timeout(30)
    void a_streamed_body_that_stops_before_its_gzip_header_fails_the_read() throws Exception {
        server.createContext("/stalled/gzip.jar", exchange -> {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, 100);
            try (var out = exchange.getResponseBody()) {
                out.write(new byte[] {0x1f, (byte) 0x8b});
                out.flush();
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Http http = new Http(Http.standardClient(), new Duration[0]).withBodyIdle(Duration.ofMillis(300));
        URI uri = base.resolve("/stalled/gzip.jar");

        CompletableFuture<HttpResponse<InputStream>> sent = CompletableFuture.supplyAsync(() -> {
            try {
                return http.getStream(uri);
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        HttpResponse<InputStream> response = sent.get(3, TimeUnit.SECONDS);
        try (InputStream in = response.body()) {
            CompletableFuture<Throwable> read = CompletableFuture.supplyAsync(() -> {
                try {
                    in.read();
                    return null;
                } catch (IOException e) {
                    return e;
                }
            });
            assertThat(read.get(3, TimeUnit.SECONDS))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(uri.toString())
                    .hasMessageContaining("no bytes")
                    .hasMessageContaining("300 ms");
        }
    }

    @Test
    @Timeout(30)
    void an_empty_gzipped_stream_answer_is_handed_back_once() throws Exception {
        AtomicInteger notModified = emptyGzip("/cached/a.pom", 304);
        AtomicInteger missing = emptyGzip("/missing/a.pom", 404);
        AtomicInteger empty = emptyGzip("/empty/a.txt", 200);
        Http http = new Http(Http.standardClient(), new Duration[] {Duration.ofMillis(10), Duration.ofMillis(10)});

        Map<String, Integer> statuses = Map.of("/cached/a.pom", 304, "/missing/a.pom", 404, "/empty/a.txt", 200);
        for (Map.Entry<String, Integer> asked : statuses.entrySet()) {
            HttpResponse<InputStream> response = http.getStream(base.resolve(asked.getKey()));
            assertThat(response.statusCode()).as(asked.getKey()).isEqualTo(asked.getValue());
            try (InputStream in = response.body()) {
                assertThat(in.readAllBytes()).as(asked.getKey()).isEmpty();
            }
        }
        assertThat(notModified.get()).as("a 304 is asked once").isEqualTo(1);
        assertThat(missing.get()).as("a 404 is asked once").isEqualTo(1);
        assertThat(empty.get()).as("an empty 200 is asked once").isEqualTo(1);
    }

    @Test
    @Timeout(30)
    void an_empty_gzipped_buffered_answer_is_handed_back_once() throws Exception {
        AtomicInteger notModified = emptyGzip("/cached/maven-metadata.xml", 304);
        AtomicInteger missing = emptyGzip("/missing/maven-metadata.xml", 404);
        Http http = new Http(Http.standardClient(), new Duration[] {Duration.ofMillis(10), Duration.ofMillis(10)});

        HttpResponse<byte[]> cached = http.get(base.resolve("/cached/maven-metadata.xml"));
        assertThat(cached.statusCode()).isEqualTo(304);
        assertThat(cached.body()).isEmpty();
        HttpResponse<byte[]> absent = http.get(base.resolve("/missing/maven-metadata.xml"));
        assertThat(absent.statusCode()).isEqualTo(404);
        assertThat(absent.body()).isEmpty();
        assertThat(notModified.get()).as("a 304 is asked once").isEqualTo(1);
        assertThat(missing.get()).as("a 404 is asked once").isEqualTo(1);
    }

    @Test
    @Timeout(30)
    void a_gzipped_stream_is_inflated_as_it_is_read() throws Exception {
        byte[] plain = "<project/>".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(packed)) {
            gz.write(plain);
        }
        byte[] wire = packed.toByteArray();
        server.createContext("/whole/a.pom", exchange -> {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, wire.length);
            try (var out = exchange.getResponseBody()) {
                out.write(wire);
            }
        });
        Http http = new Http(Http.standardClient(), new Duration[0]);

        try (InputStream in = http.getStream(base.resolve("/whole/a.pom")).body()) {
            assertThat(in.readAllBytes()).isEqualTo(plain);
        }
    }

    @Test
    @Timeout(30)
    void a_stream_that_is_not_gzip_after_all_fails_its_read() throws Exception {
        server.createContext("/liar/a.jar", exchange -> {
            byte[] body = "not gzip at all".getBytes(StandardCharsets.US_ASCII);
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        Http http = new Http(Http.standardClient(), new Duration[0]);

        try (InputStream in = http.getStream(base.resolve("/liar/a.jar")).body()) {
            assertThatThrownBy(in::readAllBytes).isInstanceOf(IOException.class);
        }
    }
}
