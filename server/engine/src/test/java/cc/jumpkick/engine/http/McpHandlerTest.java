// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.util.MiniJson;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** JK-1095 — MCP JSON-RPC tools without a full HTTP bind. */
class McpHandlerTest {

    private final EngineHttpJobs jobs = new EngineHttpJobs() {
        @Override
        public long triggerBuild(String dir) {
            return 42L;
        }

        @Override
        public long triggerTest(String dir) {
            return 43L;
        }

        @Override
        public long triggerLock(String dir) {
            return 44L;
        }

        @Override
        public boolean cancel(long requestId) {
            return requestId == 42L;
        }
    };

    private final McpHandler mcp = new McpHandler(
            () -> new StatusSnapshot(
                    "0.10.0-SNAPSHOT",
                    1L,
                    System.currentTimeMillis() - 5_000,
                    /* activeRequests */ 0,
                    /* activePipelines */ 0,
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
            "0.10.0-SNAPSHOT");

    @Test
    void initialize_returns_server_info() {
        String body = mcp.handleBody(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
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
        assertThat(text).contains("\"type\":\"status\"");
        assertThat(text).contains("\"pid\":1");
    }

    @Test
    void tools_call_build() {
        String body = mcp.handleBody(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"jk_build\",\"arguments\":{\"dir\":\"/tmp/demo\"}}}");
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) MiniJson.parse(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        @SuppressWarnings("unchecked")
        String text = (String) ((List<Map<String, Object>>) result.get("content")).getFirst().get("text");
        assertThat(text).contains("\"requestId\":42");
        assertThat(text).contains("\"type\":\"build-accepted\"");
    }

    @Test
    void tools_call_test_lock_cancel() {
        String testBody = mcp.handleBody(
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"jk_test\",\"arguments\":{\"dir\":\"/tmp/demo\"}}}");
        // Nested tool payload is JSON-escaped inside content[].text
        assertThat(testBody).contains("test-accepted");
        assertThat(testBody).contains("requestId");
        assertThat(testBody).contains("43");

        String lockBody = mcp.handleBody(
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"jk_lock\",\"arguments\":{\"dir\":\"/tmp/demo\"}}}");
        assertThat(lockBody).contains("lock-accepted");
        assertThat(lockBody).contains("44");

        String cancelBody = mcp.handleBody(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"jk_cancel\",\"arguments\":{\"requestId\":42}}}");
        assertThat(cancelBody).contains("cancelled");
        assertThat(cancelBody).contains("true");
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
