// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A response read into memory is bounded: past the limit the request fails naming the URL and the
 * limit, is not asked again, and a gzip body is bounded by what it inflates to. Metadata, POMs and
 * JSON read through {@link Http#documents()} get the smaller limit.
 */
class HttpBodyLimitTest {

    private static final int MIB = 1024 * 1024;

    private HttpServer server;
    private URI base;
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI serve(String path, byte[] body, boolean gzip) {
        server.createContext(path, exchange -> {
            calls.incrementAndGet();
            if (gzip) exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            } catch (IOException ignored) {
                // the client stopped reading
            }
        });
        return base.resolve(path);
    }

    private static Http http() {
        return new Http(Http.standardClient(), new Duration[] {Duration.ofMillis(10), Duration.ofMillis(10)});
    }

    @Test
    void a_body_over_the_limit_fails_once_naming_the_url_and_the_limit() {
        URI uri = serve("/big.json", new byte[2 * MIB], false);

        assertThatThrownBy(() -> http().withBodyLimit(MIB).get(uri))
                .isInstanceOf(BodyTooLargeException.class)
                .hasMessageContaining(uri.toString())
                .hasMessageContaining("1 MiB");
        assertThat(calls.get()).as("the same body would come back").isEqualTo(1);
    }

    @Test
    void a_gzip_body_is_bounded_by_what_it_inflates_to() throws IOException {
        URI uri = serve("/bomb.xml", gzip(new byte[4 * MIB]), true);

        assertThatThrownBy(() -> http().withBodyLimit(MIB).get(uri)).isInstanceOf(BodyTooLargeException.class);
    }

    @Test
    void a_body_within_the_limit_arrives_whole_and_inflated() throws Exception {
        byte[] body = "<metadata/>".repeat(1000).getBytes(StandardCharsets.UTF_8);
        URI plain = serve("/plain.xml", body, false);
        URI gzipped = serve("/gzipped.xml", gzip(body), true);

        assertThat(http().withBodyLimit(MIB).get(plain).body()).isEqualTo(body);
        assertThat(http().withBodyLimit(MIB).get(gzipped, Map.of()).body()).isEqualTo(body);
    }

    @Test
    void documents_stop_at_sixteen_mebibytes_and_other_bodies_at_sixty_four() throws Exception {
        URI uri = serve("/seventeen.bin", new byte[17 * MIB], false);

        assertThatThrownBy(() -> http().documents().get(uri))
                .isInstanceOf(BodyTooLargeException.class)
                .hasMessageContaining("16 MiB");
        assertThat(http().get(uri).body()).hasSize(17 * MIB);
        assertThat(Http.MAX_BODY_BYTES).isEqualTo(64L * MIB);
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }
}
