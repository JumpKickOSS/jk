// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.mcp.McpConnection;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.journal.SessionJournal;
import cc.jumpkick.engine.sessions.EpisodeInference;
import cc.jumpkick.engine.sessions.FinishedRun;
import cc.jumpkick.engine.sessions.SessionLog;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.mcp.McpRevision;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two engines over one state dir, as an upgrade leaves them: the first opens an MCP connection and
 * its session, the second displaces it, and the client carries on with the {@code Mcp-Session-Id}
 * it already holds. Each engine is its {@link McpHandler} and {@link SessionLog}; the transport
 * hands the headers in as {@code McpFront} would.
 */
class McpConnectionReplacementTest {

    private static final String HELLO = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"elicitation\":{}},"
            + "\"clientInfo\":{\"name\":\"claude-code\",\"version\":\"1\"}}}";

    @TempDir
    Path state;

    @TempDir
    Path project;

    private final List<JobSpec> specs = new ArrayList<>();

    private final EngineHttpJobs jobs = new EngineHttpJobs() {
        @Override
        public long trigger(JobSpec spec) {
            specs.add(spec);
            return 40L + specs.size();
        }

        @Override
        public boolean cancel(long requestId) {
            return false;
        }

        @Override
        public int cancelDir(String dir) {
            return 0;
        }
    };

    private final Supplier<StatusSnapshot> status = () -> new StatusSnapshot(
            "0.13.7", 1L, System.currentTimeMillis() - 5_000, 0, 0, 1L << 20, 2L << 20, 256L << 20, -1L, 8, 16L << 30);

    @Test
    void the_successor_answers_the_same_session_id_with_its_label_and_episode_and_no_initialize() {
        AtomicBoolean firstIsPrimary = new AtomicBoolean(true);
        List<FinishedRun> handedOn = new ArrayList<>();
        SessionLog firstLog = log();
        firstLog.writeWhile(firstIsPrimary::get, handedOn::add);
        McpHandler first = handler(firstLog);

        McpHandler.Reply hello = first.handle(HELLO, null, project.toString(), null, null);
        String id = requireNonNull(hello.openedSessionId());
        run(first, id);
        String label = requireNonNull(specs.getFirst().origin().session());
        String dir = specs.getFirst().dir();
        String episode = requireNonNull(firstLog.placeRun("mcp", label, "build", dir, System.currentTimeMillis()));

        // The upgrade: a new engine takes the endpoint, and the first one no longer writes.
        firstIsPrimary.set(false);
        SessionLog nextLog = log();
        McpHandler next = handler(nextLog);

        McpHandler.Reply reply = run(next, id);
        assertThat(reply.openedSessionId())
                .as("the client's id is answered, not replaced")
                .isNull();
        assertThat(specs.getLast().origin().session()).isEqualTo(label);
        assertThat(nextLog.placeRun("mcp", label, "build", dir, System.currentTimeMillis()))
                .as("the same session's episode carries on")
                .isEqualTo(episode);

        McpConnection loaded = requireNonNull(next.connection(id));
        assertThat(loaded.revision()).isEqualTo(McpRevision.V2025_06_18);
        assertThat(loaded.elicitation().form()).isTrue();
        assertThat(loaded.pinned()).isTrue();
        assertThat(loaded.dir()).isEqualTo(dir);
        assertThat(nextLog.sessions(10))
                .as("one session, not a second for a re-initialized client")
                .singleElement()
                .satisfies(s -> assertThat(s).containsEntry("label", label));
    }

    @Test
    void an_id_found_nowhere_is_a_404_on_every_path_and_a_stored_one_is_served() throws Exception {
        McpHandler first = handler(log());
        String stored = requireNonNull(
                first.handle(HELLO, null, project.toString(), null, null).openedSessionId());
        McpHandler next = handler(log());
        URI url = serve(next);
        HttpClient http = HttpClient.newHttpClient();

        HttpResponse<String> call = http.send(post(url, "beef", CALL), HttpResponse.BodyHandlers.ofString());
        assertThat(call.statusCode()).isEqualTo(404);
        assertThat(call.headers().firstValue(McpFront.SESSION_HEADER)).isEmpty();
        assertThat(call.body()).contains("\"id\":2").contains("-32001");
        assertThat(specs).as("nothing ran").isEmpty();
        HttpResponse<String> events = http.send(
                HttpRequest.newBuilder(url)
                        .header("Accept", "text/event-stream")
                        .header(McpFront.SESSION_HEADER, "beef")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(events.statusCode()).isEqualTo(404);
        HttpResponse<String> delete = http.send(
                HttpRequest.newBuilder(url)
                        .header(McpFront.SESSION_HEADER, "beef")
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(delete.statusCode()).isEqualTo(404);

        HttpResponse<String> served = http.send(post(url, stored, CALL), HttpResponse.BodyHandlers.ofString());
        assertThat(served.statusCode()).as(served.body()).isEqualTo(200);
        assertThat(served.body()).contains("\"result\"");
        assertThat(specs.getLast().origin().session()).isEqualTo("claude-code " + stored);
    }

    private static final String CALL = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
            + "{\"name\":\"run\",\"arguments\":{\"kind\":\"build\",\"wait\":false}}}";

    private @Nullable HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** {@code handler} behind {@link McpFront} on a loopback port; the {@code /mcp} URL. */
    private URI serve(McpHandler handler) throws Exception {
        HttpEvents hub = new HttpEvents();
        LiveVitals vitals = new LiveVitals(hub, () -> null, () -> null);
        McpFront front =
                new McpFront(handler, new SseEndpoint(hub, vitals, new ProgressTokenRegistry(), line -> {}), "1");
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/mcp", exchange -> {
            try (exchange) {
                front.handle(exchange);
            }
        });
        s.start();
        server = s;
        return URI.create("http://127.0.0.1:" + s.getAddress().getPort() + "/mcp");
    }

    private HttpRequest post(URI url, String session, String body) {
        return HttpRequest.newBuilder(url)
                .header("Content-Type", "application/json")
                .header(McpFront.SESSION_HEADER, session)
                .header(McpFront.PROJECT_HEADER, project.toString())
                .header(McpFront.PROTOCOL_HEADER, "2025-06-18")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private SessionLog log() {
        return new SessionLog(
                new SessionJournal(state.resolve("sessions")),
                System::currentTimeMillis,
                EpisodeInference.DEFAULT_IDLE_GAP_MILLIS);
    }

    private McpHandler handler(SessionLog log) {
        McpHandler h = new McpHandler(status, jobs, d -> Map.of("coord", "com.example:demo"), List::of, "0.13.7");
        h.sessions(log);
        return h;
    }

    private McpHandler.Reply run(McpHandler handler, @Nullable String sessionId) {
        McpHandler.Reply reply = handler.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"run\","
                        + "\"arguments\":{\"kind\":\"build\",\"wait\":false}}}",
                sessionId,
                project.toString(),
                "2025-06-18",
                null);
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) requireNonNull(MiniJson.parse(reply.body()));
        assertThat(resp).as(reply.body()).containsKey("result");
        return reply;
    }
}
