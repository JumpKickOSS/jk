// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static cc.jumpkick.engine.http.JsonFields.object;
import static cc.jumpkick.engine.http.JsonFields.objects;
import static cc.jumpkick.engine.http.JsonFields.parseObject;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpElicitResult;
import cc.jumpkick.engine.http.mcp.McpElicitation;
import cc.jumpkick.engine.http.mcp.McpTool;
import cc.jumpkick.engine.http.mcp.McpTools;
import cc.jumpkick.engine.jobs.JobSpec;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A tool call that asks the human through the client: the {@code elicitation/create} reaches a
 * client that declared the mode, the client's answer resumes the call, and a client that declared
 * nothing is never sent one.
 */
class McpElicitationTest {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object", "properties", Map.of("answer", Map.of("type", "string")), "required", List.of("answer"));

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private @Nullable HttpServer server;

    /** Asks {@link #question} and answers what came back, as text. */
    private final class AskTool implements McpTool {
        @Override
        public Spec spec() {
            return new Spec("ask", "Asks the human.", Map.of("type", "object"));
        }

        @Override
        public Map<String, Object> call(McpCall in) {
            return in.text(describe(in.elicit(question, timeout)));
        }
    }

    private volatile McpElicitation question = new McpElicitation.Form("Go ahead?", SCHEMA);
    private volatile Duration timeout = Duration.ofSeconds(20);

    private final McpHandler mcp = handler();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        threads.shutdownNow();
    }

    @Test
    void a_client_that_declared_form_mode_is_asked_and_its_answer_resumes_the_call() throws Exception {
        String session = initialize("2025-11-25", "{\"elicitation\":{\"form\":{}}}");
        BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();
        CompletableFuture<McpHandler.Reply> call =
                CompletableFuture.supplyAsync(() -> mcp.handle(CALL, session, null, null, sent::add), threads);

        Map<String, Object> request = requireNonNull(sent.poll(10, TimeUnit.SECONDS));
        assertThat(request.get("method")).isEqualTo("elicitation/create");
        assertThat(request.get("params"))
                .isEqualTo(Map.of("mode", "form", "message", "Go ahead?", "requestedSchema", SCHEMA));
        assertThat(call).isNotDone();

        String answer = "{\"jsonrpc\":\"2.0\",\"id\":\"" + request.get("id")
                + "\",\"result\":{\"action\":\"accept\",\"content\":{\"answer\":\"yes\"}}}";
        assertThat(mcp.handle(answer, session).body())
                .as("a response gets no reply")
                .isEmpty();
        assertThat(text(call.get(10, TimeUnit.SECONDS))).isEqualTo("accept {answer=yes}");
        assertThat(sent).isEmpty();
    }

    @Test
    void before_2025_11_25_a_form_request_carries_no_mode() throws Exception {
        String session = initialize("2025-06-18", "{\"elicitation\":{}}");
        BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();
        CompletableFuture<McpHandler.Reply> call =
                CompletableFuture.supplyAsync(() -> mcp.handle(CALL, session, null, null, sent::add), threads);
        Map<String, Object> request = requireNonNull(sent.poll(10, TimeUnit.SECONDS));
        assertThat(request.get("params")).isEqualTo(Map.of("message", "Go ahead?", "requestedSchema", SCHEMA));
        mcp.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":\"" + request.get("id") + "\",\"result\":{\"action\":\"decline\"}}",
                session);
        assertThat(text(call.get(10, TimeUnit.SECONDS))).isEqualTo("decline");
    }

    @Test
    void a_client_that_declared_nothing_is_never_asked() {
        BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();
        String older = initialize("2024-11-05", "{\"elicitation\":{}}");
        assertThat(text(mcp.handle(CALL, older, null, null, sent::add)))
                .isEqualTo("unsupported: the client declared no form elicitation under 2024-11-05");
        String silent = initialize("2025-11-25", "{}");
        assertThat(text(mcp.handle(CALL, silent, null, null, sent::add)))
                .isEqualTo("unsupported: the client declared no form elicitation under 2025-11-25");
        assertThat(text(mcp.handle(CALL, null, null, null, sent::add)))
                .isEqualTo("unsupported: the call rode on no session");
        assertThat(sent).isEmpty();
    }

    @Test
    void url_mode_goes_only_to_a_client_that_declared_it() throws Exception {
        question = new McpElicitation.Url("Review the change", "https://jk.example/#decision/7", "d-7");
        BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();
        String formOnly = initialize("2025-11-25", "{\"elicitation\":{}}");
        assertThat(text(mcp.handle(CALL, formOnly, null, null, sent::add)))
                .isEqualTo("unsupported: the client declared no url elicitation under 2025-11-25");
        assertThat(sent).isEmpty();

        String both = initialize("2025-11-25", "{\"elicitation\":{\"form\":{},\"url\":{}}}");
        CompletableFuture<McpHandler.Reply> call =
                CompletableFuture.supplyAsync(() -> mcp.handle(CALL, both, null, null, sent::add), threads);
        Map<String, Object> request = requireNonNull(sent.poll(10, TimeUnit.SECONDS));
        assertThat(request.get("params"))
                .isEqualTo(Map.of(
                        "mode", "url",
                        "elicitationId", "d-7",
                        "url", "https://jk.example/#decision/7",
                        "message", "Review the change"));
        mcp.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":\"" + request.get("id") + "\",\"result\":{\"action\":\"accept\"}}", both);
        assertThat(text(call.get(10, TimeUnit.SECONDS))).isEqualTo("accept {}");
    }

    @Test
    void a_question_with_no_answer_in_time_is_withdrawn() throws Exception {
        timeout = Duration.ofMillis(100);
        String session = initialize("2025-11-25", "{\"elicitation\":{}}");
        BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();
        assertThat(text(mcp.handle(CALL, session, null, null, sent::add))).isEqualTo("timed out");
        Map<String, Object> request = requireNonNull(sent.poll());
        Map<String, Object> withdrawn = requireNonNull(sent.poll());
        assertThat(withdrawn.get("method")).isEqualTo("notifications/cancelled");
        assertThat(object(withdrawn, "params").get("requestId")).isEqualTo(request.get("id"));
        // A late answer finds nothing waiting and is dropped.
        assertThat(mcp.handle(
                                "{\"jsonrpc\":\"2.0\",\"id\":\"" + request.get("id")
                                        + "\",\"result\":{\"action\":\"accept\"}}",
                                session)
                        .body())
                .isEmpty();
    }

    @Test
    void over_http_the_call_answers_as_an_event_stream_that_carries_the_question_first() throws Exception {
        URI url = serve();
        HttpClient http = HttpClient.newBuilder().executor(threads).build();
        HttpResponse<String> init = http.send(
                post(
                        url,
                        null,
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                                + "\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"elicitation\":{\"form\":{}}},"
                                + "\"clientInfo\":{\"name\":\"fake\",\"version\":\"1\"}}}"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(init.headers().firstValue("Content-Type"))
                .hasValueSatisfying(t -> assertThat(t).startsWith("application/json"));
        String session = init.headers().firstValue(McpFront.SESSION_HEADER).orElseThrow();

        HttpResponse<InputStream> call = http.send(post(url, session, CALL), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(call.headers().firstValue("Content-Type"))
                .hasValueSatisfying(t -> assertThat(t).startsWith("text/event-stream"));
        try (BufferedReader events = new BufferedReader(new InputStreamReader(call.body(), StandardCharsets.UTF_8))) {
            Map<String, Object> request = parseObject(nextData(events));
            assertThat(request.get("method")).isEqualTo("elicitation/create");

            HttpResponse<String> answered = http.send(
                    post(
                            url,
                            session,
                            "{\"jsonrpc\":\"2.0\",\"id\":\"" + request.get("id")
                                    + "\",\"result\":{\"action\":\"cancel\"}}"),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(answered.statusCode()).isEqualTo(202);

            Map<String, Object> response = parseObject(nextData(events));
            assertThat(response.get("id")).isEqualTo(2.0);
            assertThat(String.valueOf(objects(object(response, "result"), "content")
                            .getFirst()
                            .get("text")))
                    .isEqualTo("cancel");
            assertThat(events.lines().toList())
                    .as("the stream ends after the response")
                    .containsOnly("");
        }

        HttpResponse<String> plain = http.send(
                post(url, session, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(plain.headers().firstValue("Content-Type"))
                .hasValueSatisfying(t -> assertThat(t).startsWith("application/json"));
        HttpResponse<String> unknown = http.send(
                HttpRequest.newBuilder(url)
                        .header("Content-Type", "application/json")
                        .header(McpFront.PROTOCOL_HEADER, "1999-01-01")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(unknown.statusCode()).isEqualTo(400);
    }

    private static final String CALL =
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ask\",\"arguments\":{}}}";

    private URI serve() throws Exception {
        HttpEvents hub = new HttpEvents();
        LiveVitals vitals = new LiveVitals(hub, () -> null, () -> null);
        McpFront front = new McpFront(mcp, new SseEndpoint(hub, vitals, new ProgressTokenRegistry(), line -> {}), "1");
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.setExecutor(threads);
        s.createContext("/mcp", exchange -> {
            try (exchange) {
                front.handle(exchange);
            }
        });
        s.start();
        server = s;
        return URI.create("http://127.0.0.1:" + s.getAddress().getPort() + "/mcp");
    }

    private static HttpRequest post(URI url, @Nullable String session, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (session != null) {
            b.header(McpFront.SESSION_HEADER, session).header(McpFront.PROTOCOL_HEADER, "2025-11-25");
        }
        return b.build();
    }

    /** The data of the next SSE event. */
    private static String nextData(BufferedReader events) throws Exception {
        String line;
        while ((line = events.readLine()) != null) {
            if (line.startsWith("data: ")) return line.substring(6);
        }
        throw new AssertionError("the stream ended");
    }

    private String initialize(String version, String capabilities) {
        return requireNonNull(mcp.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
                                + version + "\",\"capabilities\":" + capabilities
                                + ",\"clientInfo\":{\"name\":\"fake\",\"version\":\"1\"}}}",
                        null)
                .openedSessionId());
    }

    private static String text(McpHandler.Reply reply) {
        Map<String, Object> result = object(parseObject(reply.body()), "result");
        return String.valueOf(objects(result, "content").getFirst().get("text"));
    }

    private static String describe(McpElicitResult result) {
        return switch (result) {
            case McpElicitResult.Accepted a -> "accept " + a.content();
            case McpElicitResult.Declined d -> "decline";
            case McpElicitResult.Cancelled c -> "cancel";
            case McpElicitResult.Unsupported u -> "unsupported: " + u.reason();
            case McpElicitResult.TimedOut t -> "timed out";
            case McpElicitResult.Failed f -> "failed: " + f.message();
        };
    }

    private McpHandler handler() {
        McpHandler h = new McpHandler(
                () -> new StatusSnapshot(
                        "0.13.7",
                        1L,
                        System.currentTimeMillis() - 5_000,
                        0,
                        0,
                        1L << 20,
                        2L << 20,
                        256L << 20,
                        -1L,
                        8,
                        16L << 30),
                new EngineHttpJobs() {
                    @Override
                    public long trigger(JobSpec spec) {
                        return 1L;
                    }

                    @Override
                    public boolean cancel(long requestId) {
                        return false;
                    }

                    @Override
                    public int cancelDir(String dir) {
                        return 0;
                    }
                },
                dir -> Map.of(),
                List::of,
                "0.13.7");
        h.tools(new McpTools(List.of(new AskTool())));
        return h;
    }
}
