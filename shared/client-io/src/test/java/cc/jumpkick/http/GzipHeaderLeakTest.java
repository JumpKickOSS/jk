// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A response that says {@code Content-Encoding: gzip} and starts with bytes that are not a gzip
 * header fails the first read of {@link Http#streamedBody}; closing the body then closes the
 * half-read connection rather than leaving it open.
 */
class GzipHeaderLeakTest {

    @Test
    @Timeout(30)
    void a_bad_gzip_header_closes_the_connection_it_half_read() throws Exception {
        try (ServerSocket listen = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            HttpClient client =
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            URI uri = URI.create("http://127.0.0.1:" + listen.getLocalPort() + "/x.jar");
            Thread caller = Thread.ofPlatform().start(() -> {
                try (InputStream body = client.send(
                                HttpRequest.newBuilder(uri).GET().build(),
                                Http.streamedBody(uri, Duration.ofSeconds(60)))
                        .body()) {
                    body.read();
                } catch (IOException | InterruptedException | RuntimeException expected) {
                    // the bad header fails the read; the question is what happens to the socket
                }
            });
            try (Socket s = listen.accept()) {
                readHeaders(s.getInputStream());
                OutputStream out = s.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 100000\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.write("not gzip at all".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                caller.join(10_000);
                assertThat(caller.isAlive())
                        .as("the read failed and the body was closed")
                        .isFalse();
                s.setSoTimeout(5_000);
                int read;
                try {
                    read = s.getInputStream().read();
                } catch (SocketTimeoutException stillOpen) {
                    read = -2;
                }
                assertThat(read)
                        .as("client closed the half-read connection (-1), not left it open (-2)")
                        .isEqualTo(-1);
            }
            client.shutdownNow();
        }
    }

    private static void readHeaders(InputStream in) throws IOException {
        int matched = 0;
        int b;
        byte[] end = {'\r', '\n', '\r', '\n'};
        while ((b = in.read()) >= 0) {
            matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
            if (matched == 4) return;
        }
    }
}
