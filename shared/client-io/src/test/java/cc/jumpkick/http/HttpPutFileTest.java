// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Processes;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Http#putFile} streams the file: the server receives every byte with the file's size as
 * {@code Content-Length}, and a JVM whose heap is a quarter of the file uploads it.
 */
class HttpPutFileTest {

    private static final long MIB = 1024 * 1024;

    private HttpServer server;
    private URI uri;
    private final AtomicLong received = new AtomicLong();
    private final AtomicReference<String> contentLength = new AtomicReference<>();
    private final AtomicReference<String> sha = new AtomicReference<>();
    private final AtomicReference<String> header = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/cas/", exchange -> {
            contentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            header.set(exchange.getRequestHeaders().getFirst("X-Probe"));
            MessageDigest md = Hashing.newSha256();
            long n = 0;
            try (InputStream in = exchange.getRequestBody()) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    md.update(buf, 0, r);
                    n += r;
                }
            }
            received.set(n);
            sha.set(Hashing.hex(md.digest()));
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.start();
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cas/blob");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void the_server_receives_the_file_with_its_length_and_the_headers(@TempDir Path tmp) throws Exception {
        Path file = Files.writeString(tmp.resolve("blob"), "x".repeat(300_000));

        HttpResponse<byte[]> response =
                new Http(Http.standardClient(), new Duration[0]).putFile(uri, file, Map.of("X-Probe", "yes"));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(received.get()).isEqualTo(300_000);
        assertThat(contentLength.get()).isEqualTo("300000");
        assertThat(header.get()).isEqualTo("yes");
        assertThat(sha.get()).isEqualTo(Hashing.sha256Hex(file));
    }

    /**
     * A 320 MiB file, more than the engine's heap cap, from a JVM capped at 64 MiB: a body read into
     * the heap could not fit, a streamed one does.
     */
    @Test
    void a_file_far_larger_than_the_heap_uploads_from_a_capped_jvm(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("big.bin");
        long size = 320 * MIB;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(size);
            raf.seek(size - 4);
            raf.write("tail".getBytes(StandardCharsets.UTF_8));
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-Xmx64m");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(PutFileProbe.class.getName());
        cmd.add(uri.toString());
        cmd.add(file.toString());
        Processes.Captured child = Processes.capture(cmd, Duration.ofMinutes(3));

        assertThat(child.ok()).as(child.output()).isTrue();
        assertThat(child.output()).contains("status 201");
        assertThat(received.get()).isEqualTo(size);
        assertThat(contentLength.get()).isEqualTo(Long.toString(size));
        assertThat(sha.get()).isEqualTo(Hashing.sha256Hex(file));
    }
}
