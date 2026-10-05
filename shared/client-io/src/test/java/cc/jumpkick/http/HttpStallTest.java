// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.http;

import static org.assertj.core.api.Assertions.assertThat;

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
    @Timeout(60)
    void a_server_that_never_answers_fails_once_naming_the_url() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/never/answers.pom", exchange -> {
            calls.incrementAndGet();
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        Http http = new Http(Http.standardClient(), new Duration[] {Duration.ofMillis(10), Duration.ofMillis(10)})
                .withRequestTimeout(Duration.ofSeconds(2));
        URI uri = base.resolve("/never/answers.pom");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread attempt = Thread.ofVirtual().start(() -> {
            try {
                http.get(uri);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        // The entry is the client's own, written as the request goes out, so it is read here rather
        // than from the server's handler, which a loaded host may schedule after the client gave up.
        String inFlight = awaitInFlight(uri);
        assertThat(inFlight)
                .as("while the read is parked the URL is what the process says it waits on")
                .contains("waiting on " + uri)
                .contains(" s)");
        attempt.join();

        assertThat(failure.get())
                .isInstanceOf(IOException.class)
                .hasMessageContaining(uri.toString())
                .hasMessageContaining("got no answer within 2 s");
        awaitCalls(calls);
        assertThat(calls.get()).as("a silent server is not asked again").isEqualTo(1);
        assertThat(InFlightRequests.waitingOn())
                .as("the entry ends with the request")
                .doesNotContain(uri.toString());
    }

    /** What the process says it waits on, once that names {@code uri}; a hang guard, not a deadline. */
    private static String awaitInFlight(URI uri) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            String waiting = InFlightRequests.waitingOn();
            if (waiting.contains(uri.toString())) return waiting;
            Thread.sleep(5);
        }
        return InFlightRequests.waitingOn();
    }

    /** The request was sent, so the server sees it; how soon is the scheduler's business. */
    private static void awaitCalls(AtomicInteger calls) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (calls.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
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
