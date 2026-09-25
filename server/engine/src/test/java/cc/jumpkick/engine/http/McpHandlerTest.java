// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static cc.jumpkick.engine.http.JsonFields.number;
import static cc.jumpkick.engine.http.JsonFields.object;
import static cc.jumpkick.engine.http.JsonFields.objects;
import static cc.jumpkick.engine.http.JsonFields.string;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.api.HttpLive;
import cc.jumpkick.engine.http.mcp.McpHistoryViews;
import cc.jumpkick.engine.http.mcp.McpTools;
import cc.jumpkick.engine.jobs.JobRow;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.jsonl.MiniJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**MCP JSON-RPC tools without a full HTTP bind. */
class McpHandlerTest {

    private final EngineHttpJobs jobs = new EngineHttpJobs() {
        @Override
        public long trigger(JobSpec spec) {
            return switch (spec.kind()) {
                case "test" -> 43L;
                case "lock" -> 44L;
                default -> 42L;
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
                    /* activeRequests */ 0,
                    /* activeBuildPlans */ 0,
                    1L << 20,
                    2L << 20,
                    256L << 20,
                    -1L,
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
        Map<String, Object> resp = (Map<String, Object>) requireNonNull(MiniJson.parse(body));
        assertThat(resp.get("jsonrpc")).isEqualTo("2.0");
        assertThat(resp.get("id")).isEqualTo(1.0); // MiniJson numbers are doubles
        Map<String, Object> result = object(resp, "result");
        assertThat(result.get("protocolVersion")).isEqualTo(McpHandler.PROTOCOL_VERSION);
        Map<String, Object> info = object(result, "serverInfo");
        assertThat(info.get("name")).isEqualTo("jk-engine");
        Map<String, Object> caps = object(result, "capabilities");
        assertThat(caps).containsKey("experimental");
        // The instructions are the loop and the door to the rest — the only names a default
        // client can see; the CLI mirrors and the event stream are the playbook's business.
        String instructions = String.valueOf(result.get("instructions"));
        assertThat(instructions).contains("jk_run", "jk_results", "jk_diagnostics", "jk_manual", "jk_tools");
        assertThat(instructions).doesNotContain("jk_why", "jk_status", "jk_history");
    }

    @Test
    void tools_list_includes_status_and_build_once_the_engine_serves_every_card() {
        List<Object> loop = listedNames();
        assertThat(loop).contains("jk_bind").doesNotContain("jk_status", "jk_build", "jk_history");
        mcp.surface(McpTools.Surface.ALL);
        assertThat(listedNames())
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
        Map<String, Object> resp = (Map<String, Object>) requireNonNull(MiniJson.parse(body));
        Map<String, Object> result = object(resp, "result");
        List<Map<String, Object>> content = objects(result, "content");
        String text = string(content.getFirst(), "text");
        assertThat(text).contains("pid");
        Map<String, Object> structured = object(result, "structuredContent");
        assertThat(structured.get("type")).isEqualTo("status");
        assertThat(number(structured, "pid").longValue()).isEqualTo(1L);
        // Same facts as GET /api/status: the host and epoch vitals ride too.
        assertThat(structured)
                .containsKeys(
                        "totalMemoryBytes",
                        "availableMemoryBytes",
                        "systemCpuLoad",
                        "systemLoadAverage",
                        "engineEpoch",
                        "peakActiveRequests",
                        "peakActiveBuildPlans");
    }

    @Test
    void tools_call_build() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_build\",\"arguments\":{\"dir\":\"/tmp/demo\"}}}");
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) requireNonNull(MiniJson.parse(body));
        Map<String, Object> result = object(resp, "result");
        String text = (String) objects(result, "content").getFirst().get("text");
        assertThat(text).isEqualTo("RUNNING build jid=42\njk_job action=wait jid=42\n");
        Map<String, Object> structured = object(result, "structuredContent");
        assertThat(structured.get("type")).isEqualTo("build-accepted");
        assertThat(number(structured, "jid").longValue()).isEqualTo(42L);
    }

    @Test
    void tools_call_test_lock_cancel() {
        String testBody = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_test\",\"arguments\":{\"dir\":\"/tmp/demo\"}}}");
        // Nested tool payload is JSON-escaped inside content.text
        assertThat(testBody).contains("test-accepted");
        assertThat(testBody).contains("jid");
        assertThat(testBody).contains("43");

        String lockBody = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_lock\",\"arguments\":{\"dir\":\"/tmp/demo\"}}}");
        assertThat(lockBody).contains("lock-accepted");
        assertThat(lockBody).contains("44");

        String cancelBody = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_cancel\",\"arguments\":{\"jid\":42}}}");
        assertThat(cancelBody).contains("cancelled");
        assertThat(cancelBody).contains("true");
    }

    /**
     * The {@code jobs} array of {@code jk_status} is the one {@code jk engine status --output json}
     * and {@code GET /api/status} carry: every live and queued job as its {@link JobRow}, so an agent
     * reads the same rows — and the same field names — whichever surface it asks.
     */
    @Test
    void jk_status_lists_the_jobs_as_the_status_rows() {
        List<JobRow> rows = List.of(
                JobRow.live(739, "test", "/home/me/app", 1_700_000_000_000L, 1, 1_700_000_500_000L),
                JobRow.queued(741, "format", "/home/me/tool", 1_700_000_600_000L, 0));
        StatusSnapshot base =
                new StatusSnapshot("0.12.0", 1L, 0L, 0, 1, 1L << 20, 2L << 20, 256L << 20, -1L, 8, 16L << 30);
        StatusSnapshot withJobs = new StatusSnapshot(
                base.version(),
                base.pid(),
                base.startedAtMillis(),
                base.activeRequests(),
                base.activeBuildPlans(),
                base.heapUsedBytes(),
                base.heapCommittedBytes(),
                base.heapMaxBytes(),
                base.rssBytes(),
                base.cores(),
                base.totalMemoryBytes(),
                base.availableMemoryBytes(),
                base.systemCpuLoad(),
                base.systemLoadAverage(),
                base.engineEpoch(),
                base.peakActiveRequests(),
                base.peakActiveBuildPlans(),
                base.idleDropped(),
                base.logBytes(),
                base.logRolledAt(),
                base.ignoredSignals(),
                1,
                "",
                JobRow.toJson(rows));
        McpHandler withLive = new McpHandler(
                () -> withJobs,
                jobs,
                dir -> Map.of(),
                List::of,
                "0.12.0",
                new ProgressTokenRegistry(),
                List::of,
                AdmissionYield.NONE,
                null);
        String body = withLive.handleBody(
                "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tools/call\",\"params\":{\"name\":\"jk_status\",\"arguments\":{}}}");
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) requireNonNull(MiniJson.parse(body));
        Map<String, Object> structured = object(object(resp, "result"), "structuredContent");
        List<Map<String, Object>> jobRows = objects(structured, "jobs");
        assertThat(jobRows).hasSize(2);
        assertThat(jobRows.get(0).keySet())
                .as("the row shape jk engine status and GET /api/status render")
                .containsExactly("jid", "kind", "dir", "state", "since", "workers", "lastEventAt", "ahead");
        assertThat(number(jobRows.get(0), "jid").longValue()).isEqualTo(739L);
        assertThat(jobRows.get(0).get("state")).isEqualTo("live");
        assertThat(number(jobRows.get(0), "workers").intValue()).isEqualTo(1);
        assertThat(number(jobRows.get(0), "lastEventAt").longValue()).isEqualTo(1_700_000_500_000L);
        assertThat(jobRows.get(1).get("state")).isEqualTo("queued");
        assertThat(jobRows.get(1).get("kind")).isEqualTo("format");
        assertThat(number(jobRows.get(1), "ahead").intValue()).isZero();
        assertThat(number(structured, "queuedBuildPlans").intValue()).isEqualTo(1);
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
                null,
                null,
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
                () -> new StatusSnapshot("0.12.0", 1L, 0L, 0, 0, 1L << 20, 2L << 20, 256L << 20, -1L, 8, 16L << 30),
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
                },
                null);
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
                        8,
                        16L << 30),
                jobs,
                dir -> Map.of("coord", "com.example:demo"),
                List::of,
                "0.12.0",
                tokens,
                List::of,
                AdmissionYield.NONE,
                null);
        String body = withTokens.handleBody("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_build\",\"arguments\":{\"dir\":\"/tmp/demo\"},"
                + "\"_meta\":{\"progressToken\":\"tok-1\"}}}");
        assertThat(tokens.resolve("tok-1")).isEqualTo(42L);
        assertThat(body).contains("jid=42");
        assertThat(body).contains("jid=42");
    }

    @Test
    void tools_list_includes_the_agent_followup_tools_once_the_engine_serves_every_card() {
        assertThat(listedNames()).contains("jk_manual", "jk_results").doesNotContain("jk_new", "jk_graph");
        mcp.surface(McpTools.Surface.ALL);
        assertThat(listedNames())
                .contains(
                        "jk_manual",
                        "jk_new",
                        "jk_publish",
                        "jk_install",
                        "jk_import",
                        "jk_export",
                        "jk_ide",
                        "jk_results",
                        "jk_details",
                        "jk_graph");
    }

    /** The names {@code tools/list} answers right now. */
    @SuppressWarnings("unchecked")
    private List<Object> listedNames() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        Map<String, Object> resp = (Map<String, Object>) requireNonNull(MiniJson.parse(body));
        return objects(object(resp, "result"), "tools").stream()
                .map(t -> t.get("name"))
                .toList();
    }

    @Test
    void ide_without_a_manifest_is_an_error_envelope(@TempDir Path dir) {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_ide\",\"arguments\":{\"dir\":"
                + Jsonl.quote(dir.toString())
                + ",\"preview\":true}}}");
        assertThat(body).contains("\"isError\":true");
        assertThat(body).contains("no jk.toml");
    }

    @Test
    void ide_rejects_an_unknown_kind(@TempDir Path dir) {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":23,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_ide\",\"arguments\":{\"dir\":"
                + Jsonl.quote(dir.toString())
                + ",\"kind\":\"eclipse\"}}}");
        assertThat(body).contains("-32602");
        assertThat(body).contains("kind must be idea | vscode | all");
    }

    @Test
    void publish_import_and_install_ride_jk_run(@TempDir Path dir) throws Exception {
        // The thin aliases pin the kind and go through the one runResult path.
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_publish\",\"arguments\":{\"dir\":"
                + Jsonl.quote(dir.toString())
                + ",\"wait\":false}}}");
        assertThat(body).contains("\"kind\":\"publish\"");
        assertThat(body).contains("\"jid\"");

        String install = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_import\",\"arguments\":{\"dir\":"
                + Jsonl.quote(dir.toString())
                + ",\"wait\":false}}}");
        assertThat(install).contains("\"kind\":\"import\"");
    }

    @Test
    void install_list_reports_installed_tools_without_a_job() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":23,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_install\",\"arguments\":{\"action\":\"list\"}}}");
        assertThat(body).contains("\"type\":\"tools\"");
        assertThat(body).contains("\"tools\"");
    }

    @Test
    void graph_returns_members_and_declared_deps(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "t"
                name = "app"
                version = "0.1.0"

                [dependencies]
                gson = { group = "com.google.code.gson", version = "2.11.0" }
                """);
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":24,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_graph\",\"arguments\":{\"dir\":"
                + Jsonl.quote(dir.toString())
                + "}}}");
        assertThat(body).contains("\"type\":\"graph\"");
        assertThat(body).contains("gson");
        assertThat(body).contains("\"kind\":\"module\"");
        assertThat(body).doesNotContain("\"isError\"");
    }

    /**
     * A bind key is a journal key, not a host path — it keeps its leading {@code /} on Windows
     * instead of gaining a drive letter. Collapsing {@code ..} is a separate concern and must
     * survive: a key that still says {@code /ws/../other} matches no journal row.
     */
    @Test
    void bind_collapses_dot_dot_in_an_absolute_dir_key() {
        String rec = "{\"id\":\"run-9\",\"kind\":\"build\",\"dir\":\"/other\",\"success\":true}";
        McpHandler h = new McpHandler(
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
                        8,
                        16L << 30),
                jobs,
                d -> Map.of(),
                () -> List.of(rec),
                "0.12.0");

        h.handleBody("{\"jsonrpc\":\"2.0\",\"id\":40,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_bind\",\"arguments\":{\"dir\":\"/ws/../other\"}}}");
        String history = h.handleBody("{\"jsonrpc\":\"2.0\",\"id\":41,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_history\",\"arguments\":{}}}");

        assertThat(history).contains("run-9");
    }

    /**
     * {@code jk_bind} and {@code jk_project} must derive the same key from the same argument. A
     * drive-qualified key shows the difference on any host: {@code PathUtil.resolveUserPath} reads
     * {@code C:/ws} as relative off Windows (and {@code /ws} as relative on it), so keying through
     * it lands one tool's rows under {@code $HOME} and the other's under the key the agent sent.
     */
    @Test
    void a_dir_key_keeps_its_absolute_shape_on_every_host() {
        assertThat(McpHistoryViews.dirKey("C:/ws/../app")).isEqualTo("C:/app");
        assertThat(McpHistoryViews.dirKey("/ws/../other")).isEqualTo("/other");
    }

    @Test
    void details_with_no_matching_run_is_a_tool_error() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":25,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_details\",\"arguments\":{}}}");
        // The fixture history has a finished row but no transcript on disk — still a tool error.
        assertThat(body).contains("no details.jsonl");
        assertThat(body).contains("\"isError\":true");
    }

    @Test
    void results_reads_sibling_markdown(@TempDir Path dir) throws Exception {
        Path run = dir.resolve("runs").resolve("1");
        Files.createDirectories(run);
        Files.writeString(run.resolve("jk-results.md"), "# jk results — FAIL\ncompile boom\n");
        Files.writeString(run.resolve("details.jsonl"), "{}\n");
        String rec = "{\"id\":\"run-1\",\"kind\":\"build\",\"dir\":"
                + Jsonl.quote(dir.toString())
                + ",\"success\":false,\"exitCode\":1,\"running\":false}";
        McpHandler h = new McpHandler(
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
                        8,
                        16L << 30),
                jobs,
                d -> Map.of(),
                () -> List.of(rec),
                "0.12.0");
        h.detailsFile(id -> Optional.of(run.resolve("details.jsonl")));
        String body = h.handleBody("{\"jsonrpc\":\"2.0\",\"id\":28,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_results\",\"arguments\":{}}}");
        assertThat(body).contains("FAIL build");
        assertThat(body).contains("\"type\":\"results\"");
        assertThat(body).doesNotContain("\"isError\":true");
    }

    @Test
    void results_and_details_are_advertised_as_read_only() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":30,\"method\":\"tools/list\"}");
        assertThat(body).contains("jk_results");
        assertThat(body).contains("jk_manual");
        assertThat(body).contains("readOnlyHint");
        mcp.surface(McpTools.Surface.ALL);
        String every = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":30,\"method\":\"tools/list\"}");
        assertThat(every).contains("jk_details");
        assertThat(every).contains("jk results --details");
        assertThat(every).contains("jk://runs/latest/details");
        String resources = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"resources/list\"}");
        assertThat(resources).contains("jk://runs/latest/results");
        assertThat(resources).contains("jk://runs/latest/details");
        assertThat(resources).contains("jk://manual");
        assertThat(resources).contains("text/markdown");
    }

    @Test
    void details_resource_returns_budgeted_json(@TempDir Path dir) throws Exception {
        Path run = dir.resolve("runs").resolve("1");
        Files.createDirectories(run);
        Files.writeString(
                run.resolve("details.jsonl"),
                "{\"schema\":1,\"type\":\"error\",\"message\":\"boom\"}\n"
                        + "{\"schema\":1,\"type\":\"task-finish\",\"task\":\"compile\"}\n");
        String rec = "{\"id\":\"run-1\",\"kind\":\"build\",\"dir\":"
                + Jsonl.quote(dir.toString())
                + ",\"success\":false,\"exitCode\":1,\"running\":false}";
        McpHandler h = new McpHandler(
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
                        8,
                        16L << 30),
                jobs,
                d -> Map.of(),
                () -> List.of(rec),
                "0.12.0");
        h.detailsFile(id -> Optional.of(run.resolve("details.jsonl")));
        String body = h.handleBody("{\"jsonrpc\":\"2.0\",\"id\":32,\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"jk://runs/latest/details\"}}");
        assertThat(body).contains("boom");
        assertThat(body).contains("task-finish");
        assertThat(body).contains("jk://runs/latest/details");
        assertThat(body).doesNotContain("-32602");
    }

    @Test
    void manual_returns_the_playbook_markdown() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":32,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_manual\",\"arguments\":{}}}");
        assertThat(body).contains("JumpKick playbook");
        assertThat(body).contains("jk.toml");
        assertThat(body).contains("\"type\":\"manual\"");
        assertThat(body).contains("jk://manual");
        assertThat(body).doesNotContain("\"isError\":true");
        String resource = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":33,\"method\":\"resources/read\","
                + "\"params\":{\"uri\":\"jk://manual\"}}");
        assertThat(resource).contains("text/markdown");
        assertThat(resource).contains("target/jk-results.md");
        assertThat(resource).contains("jk://manual");
    }

    @Test
    void results_with_no_file_is_a_tool_error() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":29,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_results\",\"arguments\":{}}}");
        assertThat(body).contains("FAIL build");
        assertThat(body).doesNotContain("\"isError\":true");
    }

    @Test
    void new_templates_and_preview_write_nothing(@TempDir Path parent) throws Exception {
        String templates = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":26,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_new\",\"arguments\":{\"action\":\"templates\"}}}");
        assertThat(templates).contains("builtinLayouts");

        String preview = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":27,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"jk_new\",\"arguments\":{\"name\":\"demo\",\"parentDir\":"
                + Jsonl.quote(parent.toString())
                + ",\"preview\":true}}}");
        assertThat(preview).contains("new-preview");
        assertThat(preview).contains("jk.toml");
        // Preview never touches the target.
        assertThat(Files.exists(parent.resolve("demo"))).isFalse();
    }

    @Test
    void unknown_method_is_json_rpc_error() {
        String body = mcp.handleBody("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"nope\"}");
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) requireNonNull(MiniJson.parse(body));
        assertThat(resp.get("error")).isNotNull();
        Map<String, Object> err = object(resp, "error");
        assertThat(err.get("code")).isEqualTo(-32601.0);
    }
}
