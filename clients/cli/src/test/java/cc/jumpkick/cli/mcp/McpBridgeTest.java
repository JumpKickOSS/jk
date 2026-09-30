// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.DeadEndpoint;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpBridgeTest {

    private HttpServer server;
    private final List<String> projects = new ArrayList<>();
    private final List<String> sessions = new ArrayList<>();
    private final List<String> tokens = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/mcp", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            projects.add(exchange.getRequestHeaders().getFirst(McpBridge.PROJECT_HEADER));
            sessions.add(exchange.getRequestHeaders().getFirst(McpBridge.SESSION_HEADER));
            tokens.add(exchange.getRequestHeaders().getFirst("Authorization"));
            if (body.contains("\"initialize\"")) exchange.getResponseHeaders().set(McpBridge.SESSION_HEADER, "ab12");
            if (!body.contains("\"id\"")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            byte[] out = body.contains("boom")
                    ? "nope".getBytes(StandardCharsets.UTF_8)
                    : "{\"jsonrpc\":\"2.0\",\n\"id\":1,\"result\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body.contains("boom") ? 500 : 200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private McpBridge.Endpoint live() {
        return new McpBridge.Endpoint(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"), "t0k");
    }

    private String serve(McpBridge bridge, String... lines) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String in = String.join("\n", lines) + "\n";
        bridge.serve(
                new ByteArrayInputStream(in.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void every_request_names_the_project_and_echoes_the_session() throws IOException {
        String out = serve(
                new McpBridge(this::live, "/ws/app"),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertThat(projects).containsOnly("/ws/app");
        assertThat(tokens).containsOnly("Bearer t0k");
        assertThat(sessions).containsExactly(null, "ab12", "ab12");
        // Two replies, one per line: the notification's 202 writes nothing, the body's newline is gone.
        assertThat(out.split("\n", -1)).hasSize(3).endsWith("");
        assertThat(out).doesNotContain(",\n\"id\"");
    }

    @Test
    void outside_a_project_no_project_header_is_sent() throws IOException {
        serve(new McpBridge(this::live, null), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
        assertThat(projects).containsExactly((String) null);
    }

    @Test
    void an_engine_error_is_a_json_rpc_error_for_that_id() throws IOException {
        String out = serve(new McpBridge(this::live, "/ws"), "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"boom\"}");
        assertThat(out).startsWith("{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":").contains("HTTP 500");
    }

    @Test
    void a_dead_endpoint_is_asked_for_again_once() throws IOException {
        AtomicInteger asked = new AtomicInteger();
        String out;
        try (DeadEndpoint dead = DeadEndpoint.open()) {
            McpBridge.Endpoints endpoints =
                    () -> asked.getAndIncrement() == 0 ? new McpBridge.Endpoint(dead.uri("/mcp"), "old") : live();
            out = serve(new McpBridge(endpoints, "/ws"), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
        }
        assertThat(asked).hasValue(2);
        assertThat(out).contains("\"result\"");
        assertThat(tokens).containsExactly("Bearer t0k");
    }

    @Test
    void an_unreachable_engine_answers_requests_and_drops_notifications() throws IOException {
        try (DeadEndpoint dead = DeadEndpoint.open()) {
            McpBridge bridge = new McpBridge(() -> new McpBridge.Endpoint(dead.uri("/mcp"), "x"), "/ws");
            assertThat(bridge.forward("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}"))
                    .startsWith("{\"jsonrpc\":\"2.0\",\"id\":3,\"error\":")
                    .contains("unreachable");
            assertThat(bridge.forward("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                    .isEmpty();
        }
    }
}
