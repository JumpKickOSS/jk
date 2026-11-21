// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.engine.decisions.Dashboards;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A stream whose client stops reading ends at the write deadline, the dashboard and MCP event streams
 * and an MCP call answered as an event stream alike: the handler returns and lets go of what the
 * stream held, instead of waiting on the full socket for as long as the client keeps the connection
 * open.
 */
class StreamWriteDeadlineTest {

    private static final Duration LIMIT = Duration.ofMillis(300);

    private final HttpEvents hub = new HttpEvents();
    private final LiveVitals vitals = new LiveVitals(hub, () -> null, () -> null);
    private final SseEndpoint sse =
            new SseEndpoint(hub, new Dashboards(), vitals, new ProgressTokenRegistry(), line -> {});
    private final CountDownLatch ended = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService handlers;
    private Thread flood;

    @BeforeEach
    void start() throws IOException {
        sse.writeLimit(LIMIT);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(handlers);
        server.createContext("/api/events", exchange -> {
            try (exchange) {
                sse.serveDashboard(exchange);
            } finally {
                ended.countDown();
            }
        });
        server.createContext("/mcp", exchange -> {
            try (exchange) {
                sse.serveMcp(exchange);
            } finally {
                ended.countDown();
            }
        });
        server.createContext("/call", exchange -> {
            try (exchange) {
                McpFront.Stream answer = new McpFront.Stream(exchange, LIMIT);
                String pad = "\"" + "x".repeat(64 * 1024) + "\"";
                try {
                    while (true) answer.event(pad);
                } catch (IOException cutOff) {
                    // what the test waits for
                } finally {
                    answer.close();
                }
            } finally {
                ended.countDown();
            }
        });
        server.start();
        // Frames far larger than the loopback buffers, so the stream's writes block once they fill.
        String pad = "x".repeat(64 * 1024);
        flood = Thread.ofVirtual().start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                hub.publish("request-start", JsonOut.object().put("pad", pad));
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    @AfterEach
    void stop() {
        flood.interrupt();
        server.stop(0);
        handlers.shutdownNow();
        vitals.close();
    }

    @Test
    @Timeout(30)
    void a_dashboard_stream_whose_client_stops_reading_ends_and_stops_the_samplers() throws Exception {
        try (Socket client = stalledClient("/api/events")) {
            assertThat(ended.await(10, TimeUnit.SECONDS))
                    .as("the stream ended while the client still held the connection")
                    .isTrue();
            assertThat(vitals.sampling()).as("its vitals subscriber left").isFalse();
            assertThat(hub.hasSubscribers()).isFalse();
        }
    }

    @Test
    @Timeout(30)
    void an_mcp_stream_whose_client_stops_reading_ends() throws Exception {
        try (Socket client = stalledClient("/mcp")) {
            assertThat(ended.await(10, TimeUnit.SECONDS))
                    .as("the stream ended while the client still held the connection")
                    .isTrue();
            assertThat(hub.hasSubscribers()).isFalse();
        }
    }

    @Test
    @Timeout(30)
    void an_mcp_call_answered_as_a_stream_whose_client_stops_reading_ends() throws Exception {
        try (Socket client = stalledClient("/call")) {
            assertThat(ended.await(10, TimeUnit.SECONDS))
                    .as("the call's answer ended while the client still held the connection")
                    .isTrue();
        }
    }

    /** A client that asks for the stream and never reads a byte of it. */
    private Socket stalledClient(String path) throws IOException {
        Socket client = new Socket();
        client.setReceiveBufferSize(4096);
        client.connect(server.getAddress());
        OutputStream request = client.getOutputStream();
        request.write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: text/event-stream\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        request.flush();
        return client;
    }
}
