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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * One MCP connection is one session: {@code initialize} mints the id, every {@code jk_run} on that
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

    private final McpHandler mcp = new McpHandler(
            () -> new StatusSnapshot(
                    "0.13.7",
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
            "0.13.7");

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
    void the_run_answers_its_origin_and_the_dashboard_url_that_follows_it() {
        mcp.dashboardLink(dir -> DashboardLinks.project("http://127.0.0.1:8910/", "tok-EN_1", "p-" + dir.length()));
        String id = initialize("claude-code");
        Map<String, Object> accepted = run(id);
        assertThat(accepted.get("type")).isEqualTo("job-accepted");
        assertThat(accepted.get("trigger")).isEqualTo("mcp");
        assertThat(accepted.get("session")).isEqualTo("claude-code " + id);
        assertThat(accepted.get("dashboard")).isEqualTo("http://127.0.0.1:8910/#project/p-3?t=tok-EN_1");
    }

    @Test
    void dashboard_links_carry_the_token_and_the_project_route() {
        assertThat(DashboardLinks.project("http://127.0.0.1:8910/", "T", "abc"))
                .isEqualTo("http://127.0.0.1:8910/#project/abc?t=T");
        assertThat(DashboardLinks.project("http://127.0.0.1:8910", "T", null)).isEqualTo("http://127.0.0.1:8910/#t=T");
        assertThat(DashboardLinks.project("http://127.0.0.1:8910/", null, "abc"))
                .isEqualTo("http://127.0.0.1:8910/#project/abc");
        assertThat(DashboardLinks.project(null, "T", "abc")).isNull();
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
        Map<String, Object> first = call(id, "jk_results", "{\"dir\":\"/ws\"}");
        assertThat(object(first, "structuredContent").get("bound")).isEqualTo("/ws");
        assertThat(text(first)).startsWith("bound /ws (later calls may omit dir)\n");

        // Bound: the next call omits dir and still targets /ws, with no announcement.
        Map<String, Object> second = call(id, "jk_run", "{\"kind\":\"build\",\"wait\":false}");
        assertThat(specs.getLast().dir()).isEqualTo("/ws");
        assertThat(object(second, "structuredContent")).doesNotContainKey("bound");
        assertThat(text(second)).doesNotStartWith("bound");

        // A dir on a later call is that call's target only; the bind stays.
        call(id, "jk_run", "{\"kind\":\"build\",\"wait\":false,\"dir\":\"/elsewhere\"}");
        assertThat(specs.getLast().dir()).isEqualTo("/elsewhere");
        call(id, "jk_run", "{\"kind\":\"build\",\"wait\":false}");
        assertThat(specs.getLast().dir()).isEqualTo("/ws");

        // jk_bind switches, and never announces itself.
        Map<String, Object> bound = call(id, "jk_bind", "{\"dir\":\"/other\"}");
        assertThat(object(bound, "structuredContent")).doesNotContainKey("bound");
        call(id, "jk_run", "{\"kind\":\"build\",\"wait\":false}");
        assertThat(specs.getLast().dir()).isEqualTo("/other");
    }

    @Test
    void a_bind_is_the_connection_s_own() {
        String a = initialize("claude-code");
        String b = initialize("codex");
        call(a, "jk_results", "{\"dir\":\"/ws-a\"}");

        // Connection B is still unbound: a call with no dir is a protocol error, not A's dir.
        McpHandler.Reply reply = mcp.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"jk_run\","
                        + "\"arguments\":{\"kind\":\"build\",\"wait\":false}}}",
                b);
        assertThat(reply.body()).contains("-32602").contains("requires arguments.dir");

        // B binds itself; A's bind is untouched.
        call(b, "jk_results", "{\"dir\":\"/ws-b\"}");
        call(b, "jk_run", "{\"kind\":\"build\",\"wait\":false}");
        assertThat(specs.getLast().dir()).isEqualTo("/ws-b");
        call(a, "jk_run", "{\"kind\":\"build\",\"wait\":false}");
        assertThat(specs.getLast().dir()).isEqualTo("/ws-a");

        // The catalog path binds the same way: jk_tools call carrying dir on a fresh connection.
        String c = initialize("cursor");
        Map<String, Object> viaCatalog =
                call(c, "jk_tools", "{\"action\":\"call\",\"name\":\"jk_history\",\"arguments\":{\"dir\":\"/ws-c\"}}");
        assertThat(object(viaCatalog, "structuredContent").get("bound")).isEqualTo("/ws-c");
        call(c, "jk_run", "{\"kind\":\"build\",\"wait\":false}");
        assertThat(specs.getLast().dir()).isEqualTo("/ws-c");
    }

    @Test
    void an_anonymous_call_binds_nothing() {
        Map<String, Object> result = call(null, "jk_results", "{\"dir\":\"/ws\"}");
        assertThat(object(result, "structuredContent")).doesNotContainKey("bound");
        McpHandler.Reply reply = mcp.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"jk_run\","
                        + "\"arguments\":{\"kind\":\"build\",\"wait\":false}}}",
                null);
        assertThat(reply.body()).contains("-32602").contains("requires arguments.dir");
    }

    /** {@code initialize} with {@code clientInfo.name}; returns the minted session id. */
    private String initialize(@Nullable String client) {
        String info = client == null ? "{}" : "{\"clientInfo\":{\"name\":\"" + client + "\",\"version\":\"1\"}}";
        McpHandler.Reply reply =
                mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":" + info + "}", null);
        assertThat(reply.body()).contains("\"protocolVersion\"");
        return requireNonNull(reply.openedSessionId());
    }

    private Map<String, Object> run(@Nullable String sessionId) {
        return object(
                call(sessionId, "jk_run", "{\"kind\":\"build\",\"dir\":\"/ws\",\"wait\":false}"), "structuredContent");
    }

    /** One {@code tools/call} on a connection; the whole {@code result} object. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> call(@Nullable String sessionId, String tool, String argumentsJson) {
        McpHandler.Reply reply = mcp.handle(
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
