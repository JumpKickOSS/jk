// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static cc.jumpkick.engine.http.JsonFields.object;
import static cc.jumpkick.engine.http.JsonFields.objects;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.DashboardLinks;
import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.McpHandler;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.jsonl.MiniJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * One MCP connection is one session: {@code initialize} mints the id, every {@code run} on that
 * id journals under it, a second connection is a second session, and each is bound to its own
 * project dir by the first call that names one. No HTTP bind — the session id is handed in as the
 * transport would from the {@code Mcp-Session-Id} header.
 */
class McpConnectionTest {

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

        public int cancelDir(String dir) {
            return 0;
        }
    };

    private final Supplier<StatusSnapshot> status = () -> new StatusSnapshot(
            "0.13.7", 1L, System.currentTimeMillis() - 5_000, 0, 0, 1L << 20, 2L << 20, 256L << 20, -1L, 8, 16L << 30);

    private final McpHandler mcp =
            new McpHandler(status, jobs, dir -> Map.of("coord", "com.example:demo"), List::of, "0.13.7");

    @Test
    void the_session_is_stable_on_one_connection_and_differs_across_connections() {
        String claude = initialize("claude-code");
        String codex = initialize("codex");
        assertThat(claude).matches("[0-9a-f]{4}").isNotEqualTo(codex);

        run(claude);
        run(claude);
        run(codex);
        run(null);

        assertThat(specs).hasSize(4);
        assertThat(specs.get(0).origin().trigger()).isEqualTo("mcp");
        assertThat(specs.get(0).origin().session()).isEqualTo("claude-code " + claude);
        assertThat(specs.get(1).origin().session())
                .isEqualTo(specs.get(0).origin().session());
        assertThat(specs.get(2).origin().session()).isEqualTo("codex " + codex);
        // A client that echoes no session id still runs, journaled as mcp with no session.
        assertThat(specs.get(3).origin().trigger()).isEqualTo("mcp");
        assertThat(specs.get(3).origin().session()).isNull();
    }

    @Test
    void a_detached_run_names_the_job_and_not_the_dashboard() {
        String id = initialize("claude-code");
        Map<String, Object> result = call(id, "run", "{\"kind\":\"build\",\"dir\":\"/ws\",\"wait\":false}");
        Map<String, Object> accepted = object(result, "structuredContent");
        assertThat(accepted.get("type")).isEqualTo("job-accepted");
        assertThat(accepted).doesNotContainKeys("dashboard", "session", "trigger");
        assertThat(text(result)).contains("RUNNING build jid=").contains("job action=wait jid=");
        // Who asked is still on the job the engine journals, not in the reply.
        assertThat(specs.getLast().origin().trigger()).isEqualTo("mcp");
        assertThat(specs.getLast().origin().session()).isEqualTo("claude-code " + id);
    }

    @Test
    void dashboard_links_carry_the_token_the_project_route_and_the_checkout() {
        assertThat(DashboardLinks.project("http://127.0.0.1:8910/", "T", "abc", "/ws/my app"))
                .isEqualTo("http://127.0.0.1:8910/#project/abc?dir=%2Fws%2Fmy%20app&t=T");
        assertThat(DashboardLinks.project("http://127.0.0.1:8910/", "T", "abc", null))
                .isEqualTo("http://127.0.0.1:8910/#project/abc?t=T");
        assertThat(DashboardLinks.project("http://127.0.0.1:8910", "T", null, "/ws"))
                .isEqualTo("http://127.0.0.1:8910/#t=T");
        assertThat(DashboardLinks.project("http://127.0.0.1:8910/", null, "abc", "/ws"))
                .isEqualTo("http://127.0.0.1:8910/#project/abc?dir=%2Fws");
        assertThat(DashboardLinks.project("http://127.0.0.1:8910/", null, "abc", null))
                .isEqualTo("http://127.0.0.1:8910/#project/abc");
        assertThat(DashboardLinks.project(null, "T", "abc", "/ws")).isNull();
    }

    @Test
    void an_unknown_or_closed_session_id_is_anonymous() {
        String id = initialize(null);
        run(id);
        assertThat(specs.getLast().origin().session()).isEqualTo(id); // no client name: the bare id
        assertThat(mcp.closeConnection(id)).isTrue();
        assertThat(mcp.closeConnection(id)).isFalse();
        run(id);
        assertThat(specs.getLast().origin().session()).isNull();
        run("ffff");
        assertThat(specs.getLast().origin().session()).isNull();
    }

    @Test
    void the_first_call_that_carries_dir_binds_the_connection_and_says_so_once() {
        String id = initialize("claude-code");

        // Unbound connection, a read that carries dir: the read runs, and the result says it bound.
        Map<String, Object> first = call(id, "run", "{\"run\":\"latest\",\"dir\":\"/ws\"}");
        assertThat(object(first, "structuredContent").get("bound")).isEqualTo("/ws");
        assertThat(text(first)).startsWith("bound /ws (later calls may omit dir)\n");

        // Bound: the next call omits dir and still targets /ws, with no announcement.
        Map<String, Object> second = call(id, "run", "{\"kind\":\"build\",\"wait\":false}");
        assertThat(specs.getLast().dir()).isEqualTo("/ws");
        assertThat(object(second, "structuredContent")).doesNotContainKey("bound");
        assertThat(text(second)).doesNotStartWith("bound");

        // A dir on a later call is that call's target only; the bind stays.
        call(id, "run", "{\"kind\":\"build\",\"wait\":false,\"dir\":\"/elsewhere\"}");
        assertThat(specs.getLast().dir()).isEqualTo("/elsewhere");
        call(id, "run", "{\"kind\":\"build\",\"wait\":false}");
        assertThat(specs.getLast().dir()).isEqualTo("/ws");

        // bind switches, and never announces itself.
        Map<String, Object> bound = call(id, "bind", "{\"dir\":\"/other\"}");
        assertThat(object(bound, "structuredContent")).doesNotContainKey("bound");
        call(id, "run", "{\"kind\":\"build\",\"wait\":false}");
        assertThat(specs.getLast().dir()).isEqualTo("/other");
    }

    @Test
    void a_bind_is_the_connection_s_own() {
        String a = initialize("claude-code");
        String b = initialize("codex");
        call(a, "run", "{\"run\":\"latest\",\"dir\":\"/ws-a\"}");

        // Connection B is still unbound: a call with no dir is a protocol error, not A's dir.
        McpHandler.Reply reply = mcp.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"run\","
                        + "\"arguments\":{\"kind\":\"build\",\"wait\":false}}}",
                b);
        assertThat(reply.body()).contains("-32602").contains("requires arguments.dir");

        // B binds itself; A's bind is untouched.
        call(b, "run", "{\"run\":\"latest\",\"dir\":\"/ws-b\"}");
        call(b, "run", "{\"kind\":\"build\",\"wait\":false}");
        assertThat(specs.getLast().dir()).isEqualTo("/ws-b");
        call(a, "run", "{\"kind\":\"build\",\"wait\":false}");
        assertThat(specs.getLast().dir()).isEqualTo("/ws-a");

        // An extended tool binds the same way: a direct call carrying dir on a fresh connection.
        String c = initialize("cursor");
        Map<String, Object> viaCatalog = call(c, "history", "{\"dir\":\"/ws-c\"}");
        assertThat(object(viaCatalog, "structuredContent").get("bound")).isEqualTo("/ws-c");
        call(c, "run", "{\"kind\":\"build\",\"wait\":false}");
        assertThat(specs.getLast().dir()).isEqualTo("/ws-c");
    }

    @Test
    void an_anonymous_call_binds_nothing() {
        Map<String, Object> result = call(null, "run", "{\"run\":\"latest\",\"dir\":\"/ws\"}");
        assertThat(object(result, "structuredContent")).doesNotContainKey("bound");
        McpHandler.Reply reply = mcp.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"run\","
                        + "\"arguments\":{\"kind\":\"build\",\"wait\":false}}}",
                null);
        assertThat(reply.body()).contains("-32602").contains("requires arguments.dir");
    }

    @Test
    void every_tool_and_resource_answers_for_the_calling_connection_s_own_bind() {
        // Two finished runs in two checkouts: whichever a read answers for names the run it found.
        List<String> history = List.of(
                "{\"id\":\"run-a\",\"kind\":\"build\",\"dir\":\"/ws-a\",\"running\":false,\"success\":true,"
                        + "\"startedAt\":10,\"finishedAt\":20}",
                "{\"id\":\"run-b\",\"kind\":\"build\",\"dir\":\"/ws-b\",\"running\":false,\"success\":false,"
                        + "\"startedAt\":1,\"finishedAt\":2}");
        McpHandler two =
                new McpHandler(status, jobs, dir -> Map.of("coord", "com.example:" + dir), () -> history, "0.13.7");
        String a = initialize(two, "claude-code");
        String b = initialize(two, "codex");
        call(two, a, "bind", "{\"dir\":\"/ws-a\"}");
        call(two, b, "bind", "{\"dir\":\"/ws-b\"}");

        assertThat(object(call(two, a, "status", "{}"), "structuredContent").get("boundDir"))
                .isEqualTo("/ws-a");
        assertThat(object(call(two, b, "status", "{}"), "structuredContent").get("boundDir"))
                .isEqualTo("/ws-b");
        assertThat(text(call(two, a, "run", "{\"run\":\"latest\"}")))
                .contains("ws-a")
                .doesNotContain("ws-b");
        assertThat(text(call(two, b, "run", "{\"run\":\"latest\"}")))
                .contains("ws-b")
                .doesNotContain("ws-a");
        assertThat(resource(two, a, "jk://project")).contains("\"dir\":\"/ws-a\"");
        assertThat(resource(two, b, "jk://project")).contains("\"dir\":\"/ws-b\"");

        // An anonymous reader has no bind of its own, whatever the connections bound.
        assertThat(resource(two, null, "jk://project")).contains("bind first");
        assertThat(object(call(two, null, "status", "{}"), "structuredContent")).doesNotContainKey("boundDir");
        McpHandler.Reply reply = two.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"bind\","
                        + "\"arguments\":{\"dir\":\"/ws-c\"}}}",
                null);
        assertThat(reply.body()).contains("-32602").contains("needs a connection");
        assertThat(resource(two, a, "jk://project")).contains("\"dir\":\"/ws-a\"");
    }

    /** {@code resources/read} of {@code uri} on a connection; the resource's text. */
    private static String resource(McpHandler handler, @Nullable String sessionId, String uri) {
        McpHandler.Reply reply = handler.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/read\",\"params\":{\"uri\":\"" + uri + "\"}}",
                sessionId);
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) requireNonNull(MiniJson.parse(reply.body()));
        assertThat(resp).as(reply.body()).containsKey("result");
        return String.valueOf(
                objects(object(resp, "result"), "contents").getFirst().get("text"));
    }

    /** {@code initialize} with {@code clientInfo.name}; returns the minted session id. */
    private String initialize(@Nullable String client) {
        return initialize(mcp, client);
    }

    private static String initialize(McpHandler handler, @Nullable String client) {
        String info = client == null ? "{}" : "{\"clientInfo\":{\"name\":\"" + client + "\",\"version\":\"1\"}}";
        McpHandler.Reply reply = handler.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":" + info + "}", null);
        assertThat(reply.body()).contains("\"protocolVersion\"");
        return requireNonNull(reply.openedSessionId());
    }

    private Map<String, Object> run(@Nullable String sessionId) {
        return object(
                call(sessionId, "run", "{\"kind\":\"build\",\"dir\":\"/ws\",\"wait\":false}"), "structuredContent");
    }

    /** One {@code tools/call} on a connection; the whole {@code result} object. */
    private Map<String, Object> call(@Nullable String sessionId, String tool, String argumentsJson) {
        return call(mcp, sessionId, tool, argumentsJson);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(
            McpHandler handler, @Nullable String sessionId, String tool, String argumentsJson) {
        McpHandler.Reply reply = handler.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\","
                        + "\"arguments\":" + argumentsJson + "}}",
                sessionId);
        assertThat(reply.openedSessionId()).isNull();
        Map<String, Object> resp = (Map<String, Object>) requireNonNull(MiniJson.parse(reply.body()));
        assertThat(resp).as(reply.body()).containsKey("result");
        return object(resp, "result");
    }

    private static String text(Map<String, Object> result) {
        return String.valueOf(objects(result, "content").getFirst().get("text"));
    }
}
