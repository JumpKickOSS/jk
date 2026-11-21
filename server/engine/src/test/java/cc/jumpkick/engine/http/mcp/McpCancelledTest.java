// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.McpHandler;
import cc.jumpkick.engine.http.api.EngineHttpJobs;
import cc.jumpkick.engine.http.api.ProgressTokenRegistry;
import cc.jumpkick.engine.http.api.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code notifications/cancelled} cancels the job a call started, found through the call's progress token. */
class McpCancelledTest {

    private final List<Long> cancelled = new CopyOnWriteArrayList<>();

    private final AtomicLong jids = new AtomicLong(77L);

    private final EngineHttpJobs jobs = new EngineHttpJobs() {
        @Override
        public long trigger(JobSpec spec) {
            return jids.getAndIncrement();
        }

        @Override
        public boolean cancel(long requestId) {
            cancelled.add(requestId);
            return true;
        }

        public int cancelDir(String dir) {
            return 0;
        }
    };

    private final ProgressTokenRegistry tokens = new ProgressTokenRegistry();

    private final McpHandler mcp = new McpHandler(
            () -> new StatusSnapshot("0.12.0", 1L, 0L, 0, 0, 1L << 20, 2L << 20, 256L << 20, -1L, 8, 16L << 30),
            jobs,
            dir -> Map.of(),
            List::of,
            "0.12.0",
            tokens,
            null,
            null,
            null);

    private void startRun(Path dir, String token) {
        startRun(null, dir, "\"" + token + "\"");
    }

    /** A {@code run} on {@code session} (null for none) carrying {@code tokenJson} as its progress token. */
    private void startRun(@Nullable String session, Path dir, String tokenJson) {
        String dirJson = dir.toString().replace("\\", "\\\\");
        mcp.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"run\","
                        + "\"arguments\":{\"dir\":\"" + dirJson + "\",\"wait\":false},"
                        + "\"_meta\":{\"progressToken\":" + tokenJson + "}}}",
                session);
    }

    private String open(String client) {
        McpHandler.Reply reply = mcp.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                        + "\"2025-06-18\",\"clientInfo\":{\"name\":\"" + client + "\"}}}",
                null);
        return requireNonNull(reply.openedSessionId());
    }

    private static String cancel(String meta) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":3" + meta + "}}";
    }

    @Test
    void a_cancel_carrying_the_calls_token_cancels_its_job_and_answers_nothing(@TempDir Path dir) {
        startRun(dir, "tok-9");
        assertThat(mcp.handleBody(cancel(",\"_meta\":{\"progressToken\":\"tok-9\"}")))
                .isEmpty();
        assertThat(cancelled).containsExactly(77L);
    }

    @Test
    void a_cancel_without_a_known_token_cancels_nothing(@TempDir Path dir) {
        startRun(dir, "tok-9");
        mcp.handleBody(cancel(""));
        mcp.handleBody(cancel(",\"_meta\":{\"progressToken\":\"someone-else\"}"));
        assertThat(cancelled).isEmpty();
    }

    @Test
    void two_sessions_that_send_the_same_token_each_cancel_only_their_own_job(@TempDir Path dir) {
        String a = open("agent-a");
        String b = open("agent-b");
        startRun(a, dir, "7");
        startRun(b, dir, "7");

        mcp.handle(cancel(",\"_meta\":{\"progressToken\":7}"), a);
        assertThat(cancelled)
                .as("A's cancel ends A's job, not B's, which bound 7 last")
                .containsExactly(77L);

        mcp.handle(cancel(",\"_meta\":{\"progressToken\":7}"), b);
        assertThat(cancelled).containsExactly(77L, 78L);
    }

    @Test
    void a_cancel_with_no_session_never_reaches_a_sessions_token(@TempDir Path dir) {
        String a = open("agent-a");
        startRun(a, dir, "7");
        mcp.handleBody(cancel(",\"_meta\":{\"progressToken\":7}"));
        assertThat(cancelled).isEmpty();
        assertThat(tokens.resolve(a, "7")).isEqualTo(77L);
        assertThat(tokens.resolve(null, "7")).isNull();
    }

    @Test
    void a_closed_session_takes_its_tokens_with_it(@TempDir Path dir) {
        String a = open("agent-a");
        String b = open("agent-b");
        startRun(a, dir, "7");
        startRun(b, dir, "7");
        assertThat(mcp.closeConnection(a)).isTrue();
        assertThat(tokens.resolve(a, "7")).isNull();
        assertThat(tokens.resolve(b, "7")).isEqualTo(78L);
    }
}
