// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.mcp;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.MiniJson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A request the engine sends the client while a call is outstanding travels engine to stdout, and
 * the client's answer on stdin travels back to the engine, which then answers the call. The fake
 * engine answers an {@code ask} call as an event stream: an {@code elicitation/create} first, the
 * call's response once the answer has been POSTed.
 */
class McpBridgeElicitationTest {

    private HttpServer server;
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    private final BlockingQueue<Map<String, Object>> answers = new LinkedBlockingQueue<>();
    private final List<String> versions = new CopyOnWriteArrayList<>();
    private final List<String> accepts = new CopyOnWriteArrayList<>();
    private final List<String> initializes = new CopyOnWriteArrayList<>();
    private volatile String session = "ab12";

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

    @SuppressWarnings("unchecked")
    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> m = (Map<String, Object>) requireNonNull(MiniJson.parse(body));
        versions.add(String.valueOf(exchange.getRequestHeaders().getFirst(McpBridge.PROTOCOL_HEADER)));
        accepts.add(String.valueOf(exchange.getRequestHeaders().getFirst("Accept")));
        String sent = exchange.getRequestHeaders().getFirst(McpBridge.SESSION_HEADER);
        if ("initialize".equals(m.get("method"))) {
            initializes.add(body);
            exchange.getResponseHeaders().set(McpBridge.SESSION_HEADER, session);
            json(
                    exchange,
                    "{\"jsonrpc\":\"2.0\",\"id\":" + id(m) + ",\"result\":{\"protocolVersion\":\"2025-11-25\"}}");
            return;
        }
        if (!m.containsKey("method")) {
            answers.add(m);
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }
        if (sent != null && !sent.equals(session)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        Map<String, Object> params = m.get("params") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
        if (!"ask".equals(params.get("name"))) {
            json(exchange, "{\"jsonrpc\":\"2.0\",\"id\":" + id(m) + ",\"result\":{}}");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            event(
                    out,
                    "{\"jsonrpc\":\"2.0\",\"id\":\"jk-ab12-1\",\"method\":\"elicitation/create\",\"params\":"
                            + "{\"mode\":\"form\",\"message\":\"Go?\",\"requestedSchema\":{\"type\":\"object\"}}}");
            Map<String, Object> answer = answers.poll(10, TimeUnit.SECONDS);
            String action = answer == null
                    ? "none"
                    : String.valueOf(((Map<String, Object>) requireNonNull(answer.get("result"))).get("action"));
            event(
                    out,
                    "{\"jsonrpc\":\"2.0\",\"id\":" + id(m) + ",\"result\":{\"content\":[{\"type\":\"text\","
                            + "\"text\":\"" + action + "\"}]}}");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Object id(Map<String, Object> m) {
        Object id = m.get("id");
        return id instanceof Number n ? n.longValue() : "\"" + id + "\"";
    }

    private static void event(OutputStream out, String json) throws IOException {
        out.write(("event: message\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void json(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private McpBridge.Endpoint live() {
        return new McpBridge.Endpoint(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"), "t0k");
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_engines_question_reaches_the_client_and_its_answer_resumes_the_call() throws Exception {
        PipedOutputStream client = new PipedOutputStream();
        PipedInputStream stdin = new PipedInputStream(client, 1 << 16);
        BlockingQueue<String> stdout = new LinkedBlockingQueue<>();
        McpBridge bridge = new McpBridge(this::live, "/ws");
        CompletableFuture<Void> served = CompletableFuture.runAsync(
                () -> {
                    try {
                        bridge.serve(stdin, new PrintStream(new Lines(stdout), true, StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                },
                handlers);

        send(
                client,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                        + "\"2025-11-25\",\"capabilities\":{\"elicitation\":{\"form\":{}}}}}");
        assertThat(requireNonNull(stdout.poll(10, TimeUnit.SECONDS))).contains("\"protocolVersion\":\"2025-11-25\"");
        send(client, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ask\"}}");

        Map<String, Object> question =
                (Map<String, Object>) requireNonNull(MiniJson.parse(requireNonNull(stdout.poll(10, TimeUnit.SECONDS))));
        assertThat(question.get("method")).isEqualTo("elicitation/create");
        assertThat(question.get("id")).isEqualTo("jk-ab12-1");

        send(client, "{\"jsonrpc\":\"2.0\",\"id\":\"jk-ab12-1\",\"result\":{\"action\":\"accept\",\"content\":{}}}");
        String reply = requireNonNull(stdout.poll(10, TimeUnit.SECONDS));
        assertThat(reply).startsWith("{\"jsonrpc\":\"2.0\",\"id\":2,").contains("\"text\":\"accept\"");

        client.close();
        served.get(10, TimeUnit.SECONDS);
        assertThat(stdout).as("the answer itself gets no reply").isEmpty();
        assertThat(versions).containsExactly("null", "2025-11-25", "2025-11-25");
        assertThat(accepts).allSatisfy(a -> assertThat(a).contains("text/event-stream"));
    }

    @Test
    void an_engine_that_forgot_the_session_hears_the_clients_initialize_again_then_the_call() throws Exception {
        PipedOutputStream client = new PipedOutputStream();
        PipedInputStream stdin = new PipedInputStream(client, 1 << 16);
        BlockingQueue<String> stdout = new LinkedBlockingQueue<>();
        McpBridge bridge = new McpBridge(this::live, "/ws");
        CompletableFuture<Void> served = CompletableFuture.runAsync(
                () -> {
                    try {
                        bridge.serve(stdin, new PrintStream(new Lines(stdout), true, StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                },
                handlers);
        String hello = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                + "\"2025-11-25\",\"capabilities\":{\"elicitation\":{}}}}";
        send(client, hello);
        requireNonNull(stdout.poll(10, TimeUnit.SECONDS));
        session = "cd34";
        send(client, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}");
        assertThat(requireNonNull(stdout.poll(10, TimeUnit.SECONDS))).contains("\"id\":2");
        client.close();
        served.get(10, TimeUnit.SECONDS);
        assertThat(initializes).hasSize(2).allSatisfy(body -> assertThat(body).isEqualTo(hello));
        assertThat(stdout)
                .as("the repeated handshake's reply stays in the bridge")
                .isEmpty();
    }

    private static void send(PipedOutputStream client, String line) throws IOException {
        client.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        client.flush();
    }

    /** Each line the bridge writes, as it is written. */
    private static final class Lines extends OutputStream {
        private final BlockingQueue<String> lines;
        private final StringBuilder line = new StringBuilder();

        Lines(BlockingQueue<String> lines) {
            this.lines = lines;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                lines.add(line.toString());
                line.setLength(0);
            } else {
                line.append((char) b);
            }
        }
    }
}
