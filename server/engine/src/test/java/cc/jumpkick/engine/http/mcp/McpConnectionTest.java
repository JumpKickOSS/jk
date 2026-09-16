// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static cc.jumpkick.engine.http.JsonFields.object;
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
 * id journals under it, and a second connection is a second session. No HTTP bind — the session id
 * is handed in as the transport would from the {@code Mcp-Session-Id} header.
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

    /** {@code initialize} with {@code clientInfo.name}; returns the minted session id. */
    private String initialize(@Nullable String client) {
        String info = client == null ? "{}" : "{\"clientInfo\":{\"name\":\"" + client + "\",\"version\":\"1\"}}";
        McpHandler.Reply reply =
                mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":" + info + "}", null);
        assertThat(reply.body()).contains("\"protocolVersion\"");
        return requireNonNull(reply.openedSessionId());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(@Nullable String sessionId) {
        McpHandler.Reply reply = mcp.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"jk_run\","
                        + "\"arguments\":{\"kind\":\"build\",\"dir\":\"/ws\",\"wait\":false}}}",
                sessionId);
        assertThat(reply.openedSessionId()).isNull();
        Map<String, Object> resp = (Map<String, Object>) requireNonNull(MiniJson.parse(reply.body()));
        return object(object(resp, "result"), "structuredContent");
    }
}
