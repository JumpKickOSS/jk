// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.mcp;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.testing.DeadEndpoint;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpBridgeTest {

    private HttpServer server;
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    private final List<String> projects = new CopyOnWriteArrayList<>();
    private final List<String> sessions = new CopyOnWriteArrayList<>();
    private final List<String> tokens = new CopyOnWriteArrayList<>();
    private final List<String> eventQueries = new CopyOnWriteArrayList<>();
    private volatile boolean eventsDown;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // The event stream holds its exchange open while the call it follows is answered.
        server.setExecutor(handlers);
        server.createContext("/mcp", exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                events(exchange);
                return;
            }
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
            if (body.contains("slow")) pause(1_200);
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
        handlers.shutdownNow();
    }

    /** The engine's filtered event stream: one step, one label, then quiet until the bridge hangs up. */
    private void events(HttpExchange exchange) throws IOException {
        eventQueries.add(exchange.getRequestURI().getRawQuery());
        if (eventsDown) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (var out = exchange.getResponseBody()) {
            String hello = ": mcp-events connected\n\n"
                    + frame("{\"event\":\"task-start\",\"task\":\"test\",\"progress\":40}")
                    + frame("{\"event\":\"label\",\"label\":\"FooTest#bar\",\"progress\":55.5}");
            out.write(hello.getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (!Thread.currentThread().isInterrupted()) {
                pause(50);
                out.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException e) {
            // The bridge hung up.
        }
    }

    private static String frame(String params) {
        return "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/jk/event\",\"params\":" + params
                + "}\n\n";
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private McpBridge paced() {
        return new McpBridge(this::live, "/ws", Duration.ofMillis(200), Duration.ofMillis(50));
    }

    private static String slowCall(String meta) {
        return "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"slow\"" + meta + "}}";
    }

    /** The {@code notifications/progress} params in {@code out}, in order. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> progress(String out) {
        List<Map<String, Object>> notes = new ArrayList<>();
        for (String line : out.split("\n")) {
            Map<String, Object> m = (Map<String, Object>) requireNonNull(MiniJson.parse(line));
            if ("notifications/progress".equals(m.get("method"))) notes.add((Map<String, Object>) m.get("params"));
        }
        return notes;
    }

    @Test
    void a_slow_call_with_a_progress_token_streams_progress_until_the_reply_then_stops() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        String in = slowCall(",\"_meta\":{\"progressToken\":\"tok-1\"}") + "\n";
        paced().serve(
                        new ByteArrayInputStream(in.getBytes(StandardCharsets.UTF_8)),
                        new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String out = bytes.toString(StandardCharsets.UTF_8);
        String[] lines = out.split("\n");
        assertThat(lines[lines.length - 1]).as("the reply is the last line").contains("\"result\"");
        List<Map<String, Object>> notes = progress(out);
        assertThat(notes).hasSizeGreaterThanOrEqualTo(4).allSatisfy(n -> assertThat(n.get("progressToken"))
                .isEqualTo("tok-1"));
        assertThat(notes)
                .anySatisfy(n -> assertThat(String.valueOf(n.get("message"))).isEqualTo("test · FooTest#bar · 56%"));
        double last = -1;
        for (Map<String, Object> n : notes) {
            double p = ((Number) requireNonNull(n.get("progress"))).doubleValue();
            assertThat(p).as("progress increases on every notification").isGreaterThan(last);
            last = p;
        }
        assertThat(eventQueries).containsExactly("progressToken=tok-1");
        pause(400);
        assertThat(bytes.toString(StandardCharsets.UTF_8))
                .as("nothing after the reply")
                .isEqualTo(out);
    }

    @Test
    void a_numeric_token_goes_back_as_the_number_the_client_sent() throws IOException {
        String out = serve(paced(), slowCall(",\"_meta\":{\"progressToken\":7}"));
        assertThat(out).contains("\"progressToken\":7,");
        assertThat(eventQueries).containsExactly("progressToken=7");
    }

    @Test
    void a_quiet_engine_still_gets_a_heartbeat() throws IOException {
        eventsDown = true;
        String out = serve(paced(), slowCall(",\"_meta\":{\"progressToken\":\"t\"}"));
        assertThat(progress(out)).hasSizeGreaterThanOrEqualTo(3).allSatisfy(n -> assertThat(n.get("message"))
                .isEqualTo("running"));
    }

    @Test
    void a_call_without_a_token_writes_only_its_reply() throws IOException {
        String out = serve(paced(), slowCall(""));
        assertThat(out.split("\n")).hasSize(1);
        assertThat(eventQueries).isEmpty();
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
