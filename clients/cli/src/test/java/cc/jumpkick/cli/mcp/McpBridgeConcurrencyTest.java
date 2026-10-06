// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.mcp;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.MiniJson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The bridge forwards requests concurrently and writes each reply whole on its own line, and a
 * {@code notifications/cancelled} reaches the engine carrying the cancelled call's progress token.
 * The fake engine echoes each request's id; a {@code park} call waits until a cancel names its
 * token, or {@link #PARK_MS}.
 */
class McpBridgeConcurrencyTest {

    private static final long PARK_MS = 20_000;

    private HttpServer server;
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    private final List<String> parkTokens = new CopyOnWriteArrayList<>();
    private final List<String> cancelTokens = new CopyOnWriteArrayList<>();

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
        Map<String, Object> params = m.get("params") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
        String token = params.get("_meta") instanceof Map<?, ?> meta ? String.valueOf(meta.get("progressToken")) : null;
        if (!m.containsKey("id")) {
            if ("notifications/cancelled".equals(m.get("method")) && token != null) cancelTokens.add(token);
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }
        String outcome = "done";
        if ("park".equals(params.get("name"))) {
            parkTokens.add(String.valueOf(token));
            // The cancel may reach the engine before or after this request; either way it names the token.
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PARK_MS);
            outcome = "timed out";
            while (System.nanoTime() < deadline) {
                if (cancelTokens.contains(String.valueOf(token))) {
                    outcome = "cancelled";
                    break;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // A long body, so a torn write would show as a line that does not parse.
        String reply = "{\"jsonrpc\":\"2.0\",\"id\":" + MiniJson.write(whole(m.get("id")))
                + ",\"result\":{\"outcome\":\"" + outcome + "\",\"pad\":\"" + "x".repeat(4_000) + "\"}}";
        byte[] out = reply.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    private static @Nullable Object whole(@Nullable Object id) {
        return id instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) ? n.longValue() : id;
    }

    private McpBridge bridge() {
        return new McpBridge(
                () -> new McpBridge.Endpoint(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"), "t"),
                "/ws");
    }

    private List<Map<String, Object>> serve(String... lines) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bridge().serve(
                        new ByteArrayInputStream((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8)),
                        new PrintStream(out, true, StandardCharsets.UTF_8));
        List<Map<String, Object>> replies = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) replies.add(parse(line));
        return replies;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String line) {
        return (Map<String, Object>) requireNonNull(MiniJson.parse(line));
    }

    @SuppressWarnings("unchecked")
    private static String outcome(Map<String, Object> reply) {
        return String.valueOf(((Map<String, Object>) requireNonNull(reply.get("result"))).get("outcome"));
    }

    private static String call(int id, String name) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
                + "\",\"arguments\":{\"timeout_s\":60}}}";
    }

    /**
     * The client keeps stdin open: the cancel goes in only once the ping and the second call have
     * answered, which they can do only if the parked call is not holding the bridge.
     */
    @Test
    void a_parked_call_does_not_hold_up_a_ping_or_another_call() throws Exception {
        PipedOutputStream client = new PipedOutputStream();
        PipedInputStream stdin = new PipedInputStream(client, 1 << 16);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
        long started = System.nanoTime();
        Thread serving = Thread.ofVirtual().start(() -> {
            try {
                bridge().serve(stdin, out);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        send(client, call(1, "park"), "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", call(3, "quick"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (replies(stdout).size() < 2 && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(replies(stdout))
                .as("the ping and the second call answer while the first is parked")
                .extracting(r -> r.get("id"))
                .containsExactlyInAnyOrder(2.0, 3.0);
        send(client, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":1}}");
        client.close();
        serving.join(TimeUnit.SECONDS.toMillis(10));
        List<Map<String, Object>> replies = replies(stdout);
        assertThat(replies).hasSize(3);
        assertThat(replies.getLast().get("id")).isEqualTo(1.0);
        assertThat(outcome(replies.getLast())).isEqualTo("cancelled");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                .as("the cancel released the parked call well before its own wait ran out")
                .isLessThan(PARK_MS / 2);
    }

    private static void send(PipedOutputStream client, String... lines) throws IOException {
        for (String line : lines) client.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        client.flush();
    }

    private static List<Map<String, Object>> replies(ByteArrayOutputStream stdout) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String line : stdout.toString(StandardCharsets.UTF_8).split("\n"))
            if (!line.isBlank()) out.add(parse(line));
        return out;
    }

    @Test
    void a_cancel_carries_the_progress_token_the_bridge_gave_the_call() throws IOException {
        serve(
                call(9, "park"),
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":9}}");
        assertThat(parkTokens).hasSize(1);
        assertThat(parkTokens.getFirst()).startsWith("jk-mcp-");
        assertThat(cancelTokens).containsExactlyElementsOf(parkTokens);
    }

    @Test
    void a_cancel_for_a_call_the_client_tokened_carries_the_clients_token() throws IOException {
        String tokened = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"park\","
                + "\"_meta\":{\"progressToken\":\"mine\"}}}";
        serve(tokened, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":4}}");
        assertThat(parkTokens).containsExactly("mine");
        assertThat(cancelTokens).containsExactly("mine");
    }

    @Test
    void many_concurrent_replies_each_land_whole_on_their_own_line() throws IOException {
        List<String> lines = new ArrayList<>();
        Set<Object> ids = new HashSet<>();
        for (int i = 1; i <= 2 * McpBridge.MAX_IN_FLIGHT; i++) {
            lines.add(call(i, "quick"));
            ids.add((double) i);
        }
        List<Map<String, Object>> replies = serve(lines.toArray(String[]::new));
        assertThat(replies).hasSize(ids.size());
        assertThat(replies).extracting(r -> r.get("id")).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(replies).allSatisfy(r -> assertThat(outcome(r)).isEqualTo("done"));
    }

    @Test
    void the_id_and_integral_arguments_go_to_the_engine_as_the_client_wrote_them() {
        String sent = McpBridge.withProgressToken(parse(call(12, "quick")), "t-1");
        assertThat(sent).contains("\"id\":12,").contains("\"timeout_s\":60").contains("\"progressToken\":\"t-1\"");
    }
}
