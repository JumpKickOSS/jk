// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static cc.jumpkick.engine.http.JsonFields.number;
import static cc.jumpkick.engine.http.JsonFields.object;
import static cc.jumpkick.engine.http.JsonFields.objects;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.JsonFields;
import cc.jumpkick.engine.http.McpHandler;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.jsonl.MiniJson;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
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

    private @Nullable JobSpec lastSpec;

    private final EngineHttpJobs jobs = new EngineHttpJobs() {
        @Override
        public long trigger(JobSpec spec) {
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

        public int cancelDir(String dir) {
            return 0;
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
        List<Map<String, Object>> records = objects(structured, "records");
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
        assertThat(number(page, "next").intValue()).isEqualTo(2);
        assertThat(number(page, "count").intValue()).isEqualTo(2);
        Map<String, Object> page2 = call("jk_history", "{\"limit\":2,\"next\":2}");
        assertThat(number(page2, "next").intValue()).isEqualTo(4);
        List<Map<String, Object>> rows = objects(page2, "records");
        assertThat(rows.getFirst().get("id")).isEqualTo("r2");
    }

    @Test
    void bind_filters_history_without_dir() {
        call("jk_bind", "{\"dir\":\"/ws\"}");
        Map<String, Object> hist = call("jk_history", "{}");
        assertThat(number(hist, "totalMatched").intValue()).isEqualTo(6);
        List<Map<String, Object>> records = objects(hist, "records");
        assertThat(records).allMatch(r -> "/ws".equals(r.get("dir")));
    }

    @Test
    void diagnostics_unique_last_fail() {
        Map<String, Object> d = call("jk_diagnostics", "{}");
        assertThat(d.get("type")).isEqualTo("diagnostics");
        List<Map<String, Object>> rows = objects(d, "diagnostics");
        assertThat(rows).hasSize(2);
        assertThat(number(rows.getFirst(), "count").intValue()).isEqualTo(2);
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
        assertThat(number(r, "jid").longValue()).isEqualTo(45L);
        assertThat(requireNonNull(lastSpec).kind()).isEqualTo("format");

        call(
                "jk_run",
                "{\"kind\":\"test\",\"dir\":\"/tmp\",\"wait\":false,\"include_tags\":[\"network\"],\"modules\":[\"api\"]}");
        assertThat(requireNonNull(lastSpec).kind()).isEqualTo("test");
        assertThat(requireNonNull(lastSpec).includeTags()).containsExactly("network");
        assertThat(requireNonNull(lastSpec).modules()).containsExactly("api");
    }

    @Test
    void aot_cache_is_rejected_not_silently_ignored() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_run\",\"arguments\":"
                + "{\"kind\":\"build\",\"dir\":\"/tmp\",\"wait\":false,\"aot_cache\":true}}}");
        assertThat(body).contains("-32602");
        assertThat(body).contains("aot_cache");
        String schema = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        assertThat(schema).doesNotContain("aot_cache");
    }

    @Test
    void tools_list_is_the_loop_set_until_the_engine_opts_into_every_card() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        assertThat(body).contains("jk_bind", "jk_run", "jk_results", "jk_diagnostics", "jk_deps", "jk_manifest");
        assertThat(body).contains("jk_manual", "jk_tools");
        assertThat(body).doesNotContain("jk_history", "jk_why", "jk_config", "jk_jdk");

        mcp.surface(McpTools.Surface.ALL);
        String all = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        assertThat(all).contains("jk_history", "jk_why", "jk_config", "jk_jdk", "jk_tools", "jk_run");
        assertThat(objects(JsonFields.object(JsonFields.parseObject(all), "result"), "tools"))
                .hasSameSizeAs(McpTools.standard().names());

        // Off the default list is not off the server: the card is hidden, the call still lands.
        mcp.surface(McpTools.Surface.LOOP);
        Map<String, Object> why = call("jk_history", "{}");
        assertThat(why.get("type")).isEqualTo("history");
    }

    @Test
    void initialize_capabilities_match_the_implemented_method_set() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) requireNonNull(MiniJson.parse(body));
        Map<String, Object> result = object(resp, "result");
        Map<String, Object> caps = object(result, "capabilities");
        assertThat(caps).containsKeys("tools", "resources", "prompts", "logging");
        Map<String, Object> resources = object(caps, "resources");
        assertThat(resources.get("subscribe")).isEqualTo(false);
        assertThat(resources.get("listChanged")).isEqualTo(false);
        Map<String, Object> prompts = object(caps, "prompts");
        assertThat(prompts.get("listChanged")).isEqualTo(false);
    }

    @Test
    void logging_set_level_is_a_no_op_result_not_method_not_found() {
        String body = mcp.handleBody(
                "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"logging/setLevel\"," + "\"params\":{\"level\":\"debug\"}}");
        assertThat(body).contains("\"result\"");
        assertThat(body).doesNotContain("-32601");
    }

    @Test
    void prompts_get_returns_the_listed_prompt() {
        String body = mcp.handleBody(
                "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"prompts/get\"," + "\"params\":{\"name\":\"setup-ci\"}}");
        assertThat(body).contains("apply_preset=ci");
        assertThat(body).contains("\"messages\"");
        String learn = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"prompts/get\","
                + "\"params\":{\"name\":\"learn-jumpkick\"}}");
        assertThat(learn).contains("jk_manual");
        assertThat(learn).contains("\"messages\"");
        String unknown = mcp.handleBody(
                "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"prompts/get\"," + "\"params\":{\"name\":\"nope\"}}");
        assertThat(unknown).contains("-32602");
    }

    @Test
    void resources_list_is_nonempty() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\"}");
        assertThat(body).contains("jk://session");
        assertThat(body).contains("jk://disk");
        assertThat(body).contains("jk://manual");
        assertThat(body).contains("jk://runs/latest/results");
        assertThat(body).contains("jk://runs/latest/details");
    }

    @Test
    void initialized_with_an_id_gets_an_empty_result_while_the_notification_form_stays_silent() {
        String withId = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"notifications/initialized\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) requireNonNull(MiniJson.parse(withId));
        assertThat(resp.get("id")).isEqualTo(7.0);
        assertThat(resp.get("result")).isEqualTo(Map.of());
        assertThat(mcp.handleBody("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .isEmpty();
        assertThat(mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"initialized\"}"))
                .contains("\"result\"");
    }

    @Test
    void empty_batch_is_a_single_invalid_request_error_object() {
        String body = mcp.handleBody("[]");
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) requireNonNull(MiniJson.parse(body));
        Map<String, Object> err = object(resp, "error");
        assertThat(err.get("code")).isEqualTo(-32600.0);
        assertThat(resp.get("id")).isNull();
    }

    @Test
    void all_notifications_batch_has_no_response_body() {
        String body = mcp.handleBody("[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"},"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]");
        assertThat(body).isEmpty(); // transport answers 202 with no body
    }

    @Test
    void non_object_batch_entries_get_per_item_errors_with_null_ids() {
        String body = mcp.handleBody("[1,2]");
        Object parsed = MiniJson.parse(body);
        assertThat(parsed).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) requireNonNull(parsed);
        assertThat(rows).hasSize(2);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("id")).isNull();
            Map<String, Object> err = object(row, "error");
            assertThat(err.get("code")).isEqualTo(-32600.0);
        }
    }

    @Test
    void runs_latest_skips_corrupt_and_running_records() {
        String runningStub = "{\"id\":\"live\",\"kind\":\"build\",\"dir\":\"/ws\",\"running\":true}";
        McpHandler withNoise = new McpHandler(
                () -> new StatusSnapshot("0.12.0", 1L, 0L, 0, 0, 1L << 20, 2L << 20, 256L << 20, -1L, 0, 8, 16L << 30),
                jobs,
                dir -> Map.of(),
                () -> List.of("{not json", runningStub, FAIL_A),
                "0.12.0");
        String body = withNoise.handleBody(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/read\",\"params\":{\"uri\":\"jk://runs/latest\"}}");
        assertThat(body).doesNotContain("-32603");
        assertThat(body).contains("r1"); // the next parseable finished run
        assertThat(body).doesNotContain("live");
    }

    @Test
    void tool_level_failures_set_is_error_and_successes_do_not() {
        String failing = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_jdk\",\"arguments\":{\"action\":\"install\",\"spec\":\"\"}}}");
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) requireNonNull(MiniJson.parse(failing));
        Map<String, Object> result = object(resp, "result");
        assertThat(result.get("isError")).isEqualTo(true);
        String okBody = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_status\",\"arguments\":{}}}");
        assertThat(okBody).doesNotContain("isError");
    }

    @Test
    void config_get_has_rows() {
        Map<String, Object> c = call("jk_config", "{\"action\":\"get\"}");
        assertThat(c.get("type")).isEqualTo("config");
        assertThat(c.get("rows")).isInstanceOf(List.class);
    }

    @Test
    void config_get_with_preset_ci_is_a_read_not_a_preset_apply() {
        Map<String, Object> c = call("jk_config", "{\"action\":\"get\",\"preset\":\"ci\"}");
        assertThat(c.get("type")).isEqualTo("config");
        assertThat(c.get("rows")).isInstanceOf(List.class);
        assertThat(c).doesNotContainKey("heap"); // the apply_preset result shape never appears
    }

    private Map<String, Object> call(String name, String argsJson) {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\""
                + name
                + "\",\"arguments\":"
                + argsJson
                + "}}");
        Map<String, Object> resp = (Map<String, Object>) requireNonNull(MiniJson.parse(body));
        Map<String, Object> result = object(resp, "result");
        return object(result, "structuredContent");
    }
}
