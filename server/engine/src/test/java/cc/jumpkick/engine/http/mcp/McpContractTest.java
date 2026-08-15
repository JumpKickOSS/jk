// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.McpHandler;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.plugin.protocol.MiniJson;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Envelope, bind, and summary history — no HTTP bind. */
class McpContractTest {

    private static final String FAIL_A =
            "{\"id\":\"r1\",\"buildNumber\":71,\"kind\":\"build\",\"dir\":\"/ws\",\"projectId\":\"p\","
                    + "\"success\":false,\"exitCode\":1,\"millis\":10,\"coord\":\"g:a\","
                    + "\"modules\":[{\"coord\":\"g:core\",\"success\":false}],"
                    + "\"diagnostics\":[{\"message\":\"err1\"},{\"message\":\"err2\"}]}";
    private static final String FAIL_B =
            "{\"id\":\"r2\",\"buildNumber\":70,\"kind\":\"build\",\"dir\":\"/ws\",\"projectId\":\"p\","
                    + "\"success\":false,\"exitCode\":1,\"millis\":11,\"coord\":\"g:a\","
                    + "\"modules\":[{\"coord\":\"g:core\",\"success\":false}],"
                    + "\"diagnostics\":[{\"message\":\"err1\"}]}";
    private static final String OK =
            "{\"id\":\"r3\",\"buildNumber\":69,\"kind\":\"test\",\"dir\":\"/other\",\"projectId\":\"q\","
                    + "\"success\":true,\"exitCode\":0,\"millis\":5,\"coord\":\"g:b\","
                    + "\"modules\":[],\"diagnostics\":[]}";

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
            dir -> Map.of("coord", "com.example:demo", "description", "hi"),
            () -> List.of(FAIL_A, FAIL_B, FAIL_A, FAIL_B, FAIL_A, OK),
            "0.12.0");

    @Test
    void history_default_is_summaries_without_blobs() {
        Map<String, Object> structured = call("jk_history", "{}");
        assertThat(structured.get("type")).isEqualTo("history");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) structured.get("records");
        assertThat(records).hasSize(6);
        assertThat(records.getFirst()).containsKeys("id", "success", "diagnosticCount", "failedModules");
        assertThat(records.getFirst()).doesNotContainKey("diagnostics");
        String json = MiniJson.write(structured);
        assertThat(json).doesNotContain("err1");
        assertThat(json.length()).isLessThan(8_000);
    }

    @Test
    void history_limit_and_next_page() {
        Map<String, Object> page = call("jk_history", "{\"limit\":2}");
        assertThat(page.get("truncated")).isEqualTo(true);
        assertThat(((Number) page.get("next")).intValue()).isEqualTo(2);
        assertThat(((Number) page.get("count")).intValue()).isEqualTo(2);
        Map<String, Object> page2 = call("jk_history", "{\"limit\":2,\"next\":2}");
        assertThat(((Number) page2.get("next")).intValue()).isEqualTo(4);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) page2.get("records");
        assertThat(rows.getFirst().get("id")).isEqualTo("r1");
    }

    @Test
    void bind_filters_history_without_dir() {
        call("jk_bind", "{\"dir\":\"/ws\"}");
        Map<String, Object> hist = call("jk_history", "{}");
        assertThat(((Number) hist.get("totalMatched")).intValue()).isEqualTo(5);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) hist.get("records");
        assertThat(records).allMatch(r -> "/ws".equals(r.get("dir")));
    }

    @Test
    void tools_list_includes_bind() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        assertThat(body).contains("jk_bind");
        assertThat(body).contains("jk_history");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String name, String argsJson) {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\""
                + name
                + "\",\"arguments\":"
                + argsJson
                + "}}");
        Map<String, Object> resp = (Map<String, Object>) MiniJson.parse(body);
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        return (Map<String, Object>) result.get("structuredContent");
    }
}
