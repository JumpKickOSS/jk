// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A repository that accepts a request and never answers is bounded by the request timeout, named
 * by URL while it is waited on, and not retried: the wait is paid once, not once per attempt.
 */
class HttpStallTest {

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

    @Test
    @Timeout(20)
    void a_server_that_never_answers_fails_once_naming_the_url() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> seenInFlight = new AtomicReference<>("");
        server.createContext("/never/answers.pom", exchange -> {
            calls.incrementAndGet();
            seenInFlight.set(InFlightRequests.waitingOn());
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        Http http = new Http(Http.standardClient(), new Duration[] {Duration.ofMillis(10), Duration.ofMillis(10)})
                .withRequestTimeout(Duration.ofMillis(400));
        URI uri = base.resolve("/never/answers.pom");

        long t0 = System.nanoTime();
        assertThatThrownBy(() -> http.get(uri))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(uri.toString())
                .hasMessageContaining("got no answer within 0 s");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        assertThat(calls.get()).as("a silent server is not asked again").isEqualTo(1);
        assertThat(elapsedMs).as("one timeout, not a ladder of them").isLessThan(5_000);
        assertThat(seenInFlight.get())
                .as("while the read is parked the URL is what the process says it waits on")
                .contains("waiting on " + uri)
                .contains(" s)");
        assertThat(InFlightRequests.waitingOn())
                .as("the entry ends with the request")
                .doesNotContain(uri.toString());
    }

    /** The timeout bounds the response headers; a body that trickles past it still arrives whole. */
    @Test
    @Timeout(20)
    void a_streamed_body_slower_than_the_timeout_still_arrives() throws Exception {
        byte[] body = "slow body".getBytes(StandardCharsets.UTF_8);
        server.createContext("/slow/body.jar", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                for (byte b : body) {
                    out.write(b);
                    out.flush();
                    Thread.sleep(120);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Http http = new Http(Http.standardClient(), new Duration[0]).withRequestTimeout(Duration.ofMillis(400));

        HttpResponse<InputStream> response = http.getStream(base.resolve("/slow/body.jar"));
        try (InputStream in = response.body()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("slow body");
        }
    }
}
