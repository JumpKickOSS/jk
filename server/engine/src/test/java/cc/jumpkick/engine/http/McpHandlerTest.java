// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.plugin.protocol.MiniJson;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**MCP JSON-RPC tools without a full HTTP bind. */
@Tag("integration")
class McpHandlerTest {

    private final EngineHttpJobs jobs = new EngineHttpJobs() {
        @Override
        public long trigger(JobSpec spec) {
            return 42L;
        }

        @Override
        public boolean cancel(long requestId) {
            return requestId == 42L;
        }
    };

    private final McpHandler mcp = new McpHandler(
            () -> new StatusSnapshot(
                    "0.12.0",
                    1L,
                    System.currentTimeMillis() - 5_000,
                    /* activeRequests */ 0,
                    /* activeBuildPlans */ 0,
                    1L << 20,
                    2L << 20,
                    256L << 20,
                    -1L,
                    /* aotTrainingPid */ 0,
                    /* cores */ 8,
                    16L << 30),
            jobs,
            dir -> Map.of("coord", "com.example:demo", "description", "hi"),
            () -> List.of("{\"id\":\"abc\",\"ok\":true}"),
            "0.12.0");

    @Test
    void initialize_returns_server_info() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) MiniJson.parse(body);
        assertThat(resp.get("jsonrpc")).isEqualTo("2.0");
        assertThat(resp.get("id")).isEqualTo(1.0); // MiniJson numbers are doubles
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        assertThat(result.get("protocolVersion")).isEqualTo(McpHandler.PROTOCOL_VERSION);
        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) result.get("serverInfo");
        assertThat(info.get("name")).isEqualTo("jk-engine");
        @SuppressWarnings("unchecked")
        Map<String, Object> caps = (Map<String, Object>) result.get("capabilities");
        assertThat(caps).containsKey("experimental");
        assertThat(String.valueOf(result.get("instructions"))).contains("text/event-stream");
    }

    @Test
    void tools_list_includes_status_and_build() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) MiniJson.parse(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertThat(tools.stream().map(t -> t.get("name")).toList())
                .contains(
                        "jk_status",
                        "jk_build",
                        "jk_test",
                        "jk_lock",
                        "jk_cancel",
                        "jk_bind",
                        "jk_project",
                        "jk_history");
    }

    @Test
    void tools_call_status() {
        String body = mcp.handleBody(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"jk_status\",\"arguments\":{}}}");
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) MiniJson.parse(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        String text = (String) content.getFirst().get("text");
        assertThat(text).contains("pid");
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.get("structuredContent");
        assertThat(structured.get("type")).isEqualTo("status");
        assertThat(((Number) structured.get("pid")).longValue()).isEqualTo(1L);
    }

    @Test
    void tools_call_build() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_build\",\"arguments\":{\"dir\":\"/tmp/demo\"}}}");
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) MiniJson.parse(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        @SuppressWarnings("unchecked")
        String text = (String)
                ((List<Map<String, Object>>) result.get("content")).getFirst().get("text");
        assertThat(text).isEqualTo("build accepted"); // summary only; payload is structured
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.get("structuredContent");
        assertThat(structured.get("type")).isEqualTo("build-accepted");
        assertThat(((Number) structured.get("requestId")).longValue()).isEqualTo(42L);
    }

    @Test
    void tools_call_test_lock_cancel() {
        String testBody = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_test\",\"arguments\":{\"dir\":\"/tmp/demo\"}}}");
        // Nested tool payload is JSON-escaped inside content.text
        assertThat(testBody).contains("test-accepted");
        assertThat(testBody).contains("requestId");
        assertThat(testBody).contains("43");

        String lockBody = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_lock\",\"arguments\":{\"dir\":\"/tmp/demo\"}}}");
        assertThat(lockBody).contains("lock-accepted");
        assertThat(lockBody).contains("44");

        String cancelBody = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_cancel\",\"arguments\":{\"requestId\":42}}}");
        assertThat(cancelBody).contains("cancelled");
        assertThat(cancelBody).contains("true");
    }

    @Test
    void stalled_keys_on_event_silence_not_job_age() {
        long now = System.currentTimeMillis();
        // Old job, fresh progress signal: healthy. Old job, silent for the stall window: stalled.
        HttpLive.Run healthy = new HttpLive.Run(
                1L,
                1L,
                "build",
                "/a",
                "c",
                now - 10 * 60_000,
                now - 1_000,
                40.0,
                "j-1",
                0,
                0,
                1,
                2,
                List.of(),
                List.of());
        HttpLive.Run silent = new HttpLive.Run(
                2L,
                2L,
                "build",
                "/b",
                "c",
                now - 10 * 60_000,
                now - McpHandler.STALL_MS - 5_000,
                40.0,
                "j-2",
                0,
                0,
                1,
                2,
                List.of(),
                List.of());
        // No signal ever: falls back to startedAt (young job — not stalled).
        HttpLive.Run young = new HttpLive.Run(
                3L, 3L, "lock", "/c", "c", now - 2_000, 0L, Double.NaN, "j-3", 0, 0, 1, 2, List.of(), List.of());
        McpHandler withLive = new McpHandler(
                () -> new StatusSnapshot("0.12.0", 1L, 0L, 0, 0, 1L << 20, 2L << 20, 256L << 20, -1L, 0, 8, 16L << 30),
                jobs,
                dir -> Map.of(),
                List::of,
                "0.12.0",
                new ProgressTokenRegistry(),
                () -> List.of(healthy, silent, young));
        String body = withLive.handleBody(
                "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tools/call\",\"params\":{\"name\":\"jk_status\",\"arguments\":{}}}");
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) MiniJson.parse(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.get("structuredContent");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jobRows = (List<Map<String, Object>>) structured.get("jobs");
        assertThat(jobRows).hasSize(3);
        assertThat(jobRows.get(0).get("stalled")).isEqualTo(false); // ten minutes old, ticked 1s ago
        assertThat(jobRows.get(1).get("stalled")).isEqualTo(true); // silent past the stall window
        assertThat(jobRows.get(2).get("stalled")).isEqualTo(false); // no signal, but only 2s old
    }

    @Test
    void run_wait_parks_inside_the_admission_yield_scope() {
        AtomicInteger polls = new AtomicInteger();
        AtomicInteger yields = new AtomicInteger();
        HttpLive.Run live = new HttpLive.Run(
                42L,
                1L,
                "build",
                "/tmp/demo",
                "com.example:demo",
                1L,
                0L,
                50.0,
                "j-1",
                0,
                0,
                1,
                2,
                List.of(),
                List.of());
        McpHandler waiting = new McpHandler(
                () -> new StatusSnapshot("0.12.0", 1L, 0L, 0, 0, 1L << 20, 2L << 20, 256L << 20, -1L, 0, 8, 16L << 30),
                jobs,
                dir -> Map.of(),
                List::of,
                "0.12.0",
                new ProgressTokenRegistry(),
                // Live for the first two polls, then gone — the wait loop must see both states.
                () -> polls.incrementAndGet() <= 2 ? List.of(live) : List.of(),
                new AdmissionYield() {
                    @Override
                    public <T> T yielding(Supplier<T> blocking) {
                        yields.incrementAndGet();
                        return blocking.get();
                    }
                });
        String body = waiting.handleBody("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_run\",\"arguments\":{\"dir\":\"/tmp/demo\",\"wait\":true}}}");
        assertThat(body).contains("\"finished\":true");
        // Both the live-run park and the journal lookup ran with the RPC permit yielded.
        assertThat(yields.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void tools_call_binds_progress_token() {
        ProgressTokenRegistry tokens = new ProgressTokenRegistry();
        McpHandler withTokens = new McpHandler(
                () -> new StatusSnapshot(
                        "0.12.0",
                        1L,
                        System.currentTimeMillis() - 5_000,
                        0,
                        0,
                        1L << 20,
                        2L << 20,
                        256L << 20,
                        -1L,
                        0,
                        8,
                        16L << 30),
                jobs,
                dir -> Map.of("coord", "com.example:demo"),
                List::of,
                "0.12.0",
                tokens);
        String body = withTokens.handleBody("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_build\",\"arguments\":{\"dir\":\"/tmp/demo\"},"
                + "\"_meta\":{\"progressToken\":\"tok-1\"}}}");
        assertThat(body).contains("progressToken");
        assertThat(body).contains("tok-1");
        assertThat(tokens.resolve("tok-1")).isEqualTo(42L);
        assertThat(body).contains("requestId=42");
    }

    @Test
    void unknown_method_is_json_rpc_error() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"nope\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) MiniJson.parse(body);
        assertThat(resp.get("error")).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> err = (Map<String, Object>) resp.get("error");
        assertThat(err.get("code")).isEqualTo(-32601.0);
    }
}
