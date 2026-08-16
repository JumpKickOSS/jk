// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.HttpJobSpec;
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
    private static final String COMPILE_FAIL =
            "{\"id\":\"r4\",\"buildNumber\":72,\"kind\":\"build\",\"dir\":\"/ws\",\"projectId\":\"p\","
                    + "\"success\":false,\"exitCode\":1,\"millis\":12,\"coord\":\"g:a\","
                    + "\"modules\":[{\"coord\":\"g:core\",\"success\":false}],"
                    + "\"diagnostics\":["
                    + "{\"severity\":\"error\",\"code\":\"javac\",\"dir\":\"/ws/core\","
                    + "\"message\":\"/ws/A.java:1: error: cannot find symbol\\n  foo\\n  ^\"},"
                    + "{\"severity\":\"error\",\"code\":\"javac\",\"dir\":\"/ws/core\","
                    + "\"message\":\"/ws/A.java:1: error: cannot find symbol\\n  foo\\n  ^\"},"
                    + "{\"severity\":\"error\",\"code\":\"javac\",\"dir\":\"/ws/core\","
                    + "\"message\":\"/ws/B.java:2:5: error: ctor\\n  required: none\"}"
                    + "]}";
    private static final String OK =
            "{\"id\":\"r3\",\"buildNumber\":69,\"kind\":\"test\",\"dir\":\"/other\",\"projectId\":\"q\","
                    + "\"success\":true,\"exitCode\":0,\"millis\":5,\"coord\":\"g:b\","
                    + "\"modules\":[],\"diagnostics\":[]}";

    private HttpJobSpec lastSpec;

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
        public long trigger(HttpJobSpec spec) {
            lastSpec = spec;
            return switch (spec.kind()) {
                case "test" -> 43L;
                case "lock", "update" -> 44L;
                default -> 45L;
            };
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
            () -> List.of(COMPILE_FAIL, FAIL_A, FAIL_B, FAIL_A, FAIL_B, FAIL_A, OK),
            "0.12.0");

    @Test
    void history_default_is_summaries_without_blobs() {
        Map<String, Object> structured = call("jk_history", "{}");
        assertThat(structured.get("type")).isEqualTo("history");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) structured.get("records");
        assertThat(records).hasSize(7);
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
        assertThat(rows.getFirst().get("id")).isEqualTo("r2");
    }

    @Test
    void bind_filters_history_without_dir() {
        call("jk_bind", "{\"dir\":\"/ws\"}");
        Map<String, Object> hist = call("jk_history", "{}");
        assertThat(((Number) hist.get("totalMatched")).intValue()).isEqualTo(6);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) hist.get("records");
        assertThat(records).allMatch(r -> "/ws".equals(r.get("dir")));
    }

    @Test
    void diagnostics_unique_last_fail() {
        Map<String, Object> d = call("jk_diagnostics", "{}");
        assertThat(d.get("type")).isEqualTo("diagnostics");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) d.get("diagnostics");
        assertThat(rows).hasSize(2);
        assertThat(((Number) rows.getFirst().get("count")).intValue()).isEqualTo(2);
        assertThat(rows.getFirst().get("file")).isEqualTo("/ws/A.java");
        assertThat(MiniJson.write(d).length()).isLessThan(4_096);
    }

    @Test
    void run_wait_returns_job_envelope() {
        Map<String, Object> r = call("jk_run", "{\"kind\":\"test\",\"dir\":\"/tmp\",\"wait\":true,\"timeout_s\":2}");
        assertThat(r.get("type")).isIn("job", "job-accepted", "test-accepted");
        assertThat(r.get("jid")).isNotNull();
    }

    @Test
    void run_hosts_format_and_applies_tags() {
        Map<String, Object> r = call("jk_run", "{\"kind\":\"format\",\"dir\":\"/tmp\",\"wait\":false}");
        assertThat(r.get("type")).isEqualTo("job-accepted");
        assertThat(((Number) r.get("jid")).longValue()).isEqualTo(45L);
        assertThat(lastSpec.kind()).isEqualTo("format");

        call(
                "jk_run",
                "{\"kind\":\"test\",\"dir\":\"/tmp\",\"wait\":false,\"include_tags\":[\"network\"],\"modules\":[\"api\"]}");
        assertThat(lastSpec.kind()).isEqualTo("test");
        assertThat(lastSpec.includeTags()).containsExactly("network");
        assertThat(lastSpec.modules()).containsExactly("api");
    }

    @Test
    void tools_list_includes_bind() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        assertThat(body).contains("jk_bind");
        assertThat(body).contains("jk_history");
        assertThat(body).contains("jk_diagnostics");
        assertThat(body).contains("jk_run");
        assertThat(body).contains("jk_why");
        assertThat(body).contains("jk_config");
        assertThat(body).contains("jk_jdk");
    }

    @Test
    void initialize_capabilities_match_the_implemented_method_set() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) MiniJson.parse(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        @SuppressWarnings("unchecked")
        Map<String, Object> caps = (Map<String, Object>) result.get("capabilities");
        assertThat(caps).containsKeys("tools", "resources", "prompts", "logging");
        @SuppressWarnings("unchecked")
        Map<String, Object> resources = (Map<String, Object>) caps.get("resources");
        assertThat(resources.get("subscribe")).isEqualTo(false);
        assertThat(resources.get("listChanged")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> prompts = (Map<String, Object>) caps.get("prompts");
        assertThat(prompts.get("listChanged")).isEqualTo(false);
    }

    @Test
    void logging_set_level_is_a_no_op_result_not_method_not_found() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"logging/setLevel\","
                + "\"params\":{\"level\":\"debug\"}}");
        assertThat(body).contains("\"result\"");
        assertThat(body).doesNotContain("-32601");
    }

    @Test
    void prompts_get_returns_the_listed_prompt() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"prompts/get\","
                + "\"params\":{\"name\":\"setup-ci\"}}");
        assertThat(body).contains("apply_preset=ci");
        assertThat(body).contains("\"messages\"");
        String unknown = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"prompts/get\","
                + "\"params\":{\"name\":\"nope\"}}");
        assertThat(unknown).contains("-32602");
    }

    @Test
    void resources_list_is_nonempty() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\"}");
        assertThat(body).contains("jk://session");
        assertThat(body).contains("jk://disk");
    }

    @Test
    void config_get_has_rows() {
        Map<String, Object> c = call("jk_config", "{\"action\":\"get\"}");
        assertThat(c.get("type")).isEqualTo("config");
        assertThat(c.get("rows")).isInstanceOf(List.class);
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
