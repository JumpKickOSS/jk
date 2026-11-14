// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The engine answers a {@code tools/call} as an event stream, sends one request of its own and
 * closes the stream without the call's response (it crashed, or was stopped). The bridge must
 * answer the call's id with an error; an empty reply writes nothing and the client waits forever.
 */
class McpBridgeEarlyStreamCloseTest {

    private HttpServer server;
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(handlers);
        server.createContext("/mcp", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        handlers.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.contains("\"notifications/")) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            String ask = "{\"jsonrpc\":\"2.0\",\"id\":\"jk-1\",\"method\":\"elicitation/create\",\"params\":{}}";
            out.write(("event: message\ndata: " + ask + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private McpBridge bridge() {
        return new McpBridge(
                () -> new McpBridge.Endpoint(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"), "t0k"),
                null);
    }

    @Test
    void a_notification_accepted_with_202_still_gets_no_reply() {
        assertThat(bridge().forward("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .isEmpty();
    }

    @Test
    void a_stream_that_ends_without_the_response_answers_the_call_with_an_error() {
        String reply = bridge().forward(
                        "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"build\"}}");
        assertThat(reply)
                .as("the reply the client gets for tools/call id 7")
                .contains("\"id\":7")
                .contains("\"error\"");
    }
}
