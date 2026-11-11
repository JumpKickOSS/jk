// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.testing.LoopbackHttp;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** How the retry ladder reads a rate limit, a refusal raced by another request, and a mixed failure. */
class HttpRetryTest {

    @RegisterExtension
    final LoopbackHttp central = new LoopbackHttp();

    @RegisterExtension
    final LoopbackHttp mirror = new LoopbackHttp().host("localhost");

    @AfterEach
    void forgetFaults() {
        ConnectFaults.forget();
    }

    @Test
    void a_short_retry_after_is_waited_out_and_the_request_asked_again() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        central.server().createContext("/limited", exchange -> {
            if (hits.getAndIncrement() == 0) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                exchange.sendResponseHeaders(429, -1);
            } else {
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });

        HttpResponse<byte[]> response = http(CentralMirror.isolated(Path.of("unused"), false))
                .get(central.base().resolve("/limited"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(hits).hasValue(2);
    }

    /** A request sent to Central before another request opened the mirror window still reaches the mirror. */
    @Test
    void a_central_429_raced_by_another_refusal_is_reissued_on_the_mirror(@TempDir Path dir) throws Exception {
        CentralMirror m = new CentralMirror(
                dir, Duration.ofHours(4), true, "127.0.0.1", mirror.baseUrl().replaceAll("/$", "") + "/mirror");
        central.server().createContext("/maven2/", exchange -> {
            m.noteRateLimited(); // the other request's refusal landed first
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
        });
        mirror.serve("/mirror/g/a/maven-metadata.xml", "<metadata/>");

        HttpResponse<byte[]> response = http(m).get(central.base().resolve("/maven2/g/a/maven-metadata.xml"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("<metadata/>");
    }

    /** A Central credential does not travel to the mirror the window sends the request to. */
    @Test
    void a_request_routed_to_the_mirror_drops_the_central_credential(@TempDir Path dir) throws Exception {
        CentralMirror m = new CentralMirror(
                dir, Duration.ofHours(4), true, "127.0.0.1", mirror.baseUrl().replaceAll("/$", "") + "/mirror");
        m.noteRateLimited();
        mirror.serve("/mirror/g/a/1/a-1.pom", "<project/>");

        HttpResponse<byte[]> response = http(m).get(
                        central.base().resolve("/maven2/g/a/1/a-1.pom"),
                        Map.of("Authorization", "Bearer central-only"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mirror.headersFor("/mirror/g/a/1/a-1.pom"))
                .get()
                .extracting(h -> h.get("Authorization"))
                .isNull();
    }

    /**
     * An address that answered with a status on some attempts is up: a connect fault on another
     * attempt neither makes the failure "no answer" nor puts the address out of reach.
     */
    @Test
    void one_connect_fault_among_answered_attempts_is_not_no_answer() throws Exception {
        ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        int port = listener.getLocalPort();
        Thread server = new Thread(() -> {
            try (listener) {
                for (int i = 0; i < 2; i++) {
                    try (Socket s = listener.accept()) {
                        readHead(s.getInputStream());
                        OutputStream out = s.getOutputStream();
                        out.write("HTTP/1.1 503 Busy\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    }
                }
            } catch (IOException ignored) {
                // the test fails on what the client saw
            }
        });
        server.start();
        Http http = new Http(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build(),
                new Duration[] {Duration.ofMillis(50), Duration.ofMillis(50)},
                CentralMirror.isolated(Path.of("unused"), false));
        URI uri = URI.create("http://127.0.0.1:" + port + "/a.pom");

        assertThatThrownBy(() -> http.get(uri))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(Http.NoAnswerException.class)
                .hasMessageContaining("returned 503");
        server.join(5_000);
        assertThat(ConnectFaults.refusing("127.0.0.1:" + port)).isNull();
    }

    /** A request with a timeout of its own is named with that timeout when it meets silence. */
    @Test
    void the_no_answer_message_names_the_request_s_own_timeout() {
        URI uri = URI.create("https://repo.example/upload");
        HttpRequest request =
                HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).build();

        Http.NoAnswerException failure = http(CentralMirror.isolated(Path.of("unused"), false))
                .noAnswerWithin("PUT", uri, request, new HttpTimeoutException("silent"));

        assertThat(failure).hasMessageContaining("within 120 s");
    }

    private static void readHead(InputStream in) throws IOException {
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        int c;
        while (matched < end.length && (c = in.read()) >= 0) {
            matched = c == end[matched] ? matched + 1 : (c == '\r' ? 1 : 0);
        }
    }

    private static Http http(CentralMirror m) {
        return new Http(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                new Duration[] {Duration.ofMillis(1), Duration.ofMillis(1)},
                m);
    }
}
