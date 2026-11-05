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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
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
    void an_id_found_nowhere_is_handed_a_new_connection_on_a_pinned_client() {
        McpHandler next = handler(log());

        McpHandler.Reply reply = run(next, "beef");

        assertThat(reply.openedSessionId()).isNotNull().isNotEqualTo("beef");
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
