// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.sessions.EpisodeInference;
import cc.jumpkick.engine.sessions.SessionLog;
import cc.jumpkick.mcp.McpRevision;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A logged MCP call reaches its connection's session as one compact action — what it asked, the
 * answer's headline, its outcome — and a call the timeline does not show leaves no action.
 */
class McpActionsTest {

    @TempDir
    Path project;

    private final SessionLog log = new SessionLog(
            null,
            System::currentTimeMillis,
            EpisodeInference.DEFAULT_IDLE_GAP_MILLIS,
            JkHttpConfig.Mcp.DEFAULT_SESSION_EXPIRY.toMillis());

    @Test
    void a_graph_question_and_a_dependency_change_are_logged_in_display_form() {
        McpContext ctx = context();
        McpConnection connection = ctx.connections().open("claude-code", null, McpRevision.LATEST, McpElicitModes.NONE);
        String id = log.open("mcp", "claude-code", connection.label(), null);
        McpTools tools = new McpTools(List.of(
                tool("callers", in -> in.text("3 callers\nQuote.total\n")),
                tool("deps", in -> in.error("no such artifact org.postgresql:postgresql\n")),
                tool("status", in -> in.text("ok\n"))));

        tools.call(
                ctx,
                call("callers", Map.of("dir", project.toString(), "address", "PricingRules.weekendUplift")),
                connection,
                null);
        tools.call(
                ctx,
                call("deps", Map.of("action", "add", "coords", List.of("org.postgresql:postgresql"))),
                connection,
                null);
        tools.call(ctx, call("status", Map.of()), connection, null);

        SessionLog.Page page = log.session(id, 0, 10).orElseThrow();
        assertThat(page.actions()).extracting(a -> a.get("kind")).containsExactly("callers", "deps");
        Map<String, Object> callers = page.actions().getFirst();
        assertThat(callers)
                .containsEntry("summary", "callers PricingRules.weekendUplift")
                .containsEntry("headline", "3 callers")
                .containsEntry("outcome", "ok");
        assertThat(page.actions().get(1))
                .containsEntry("summary", "deps add org.postgresql:postgresql")
                .containsEntry("outcome", "error");
        // The deps call named no dir: it acted on the project the connection's first call bound.
        assertThat(page.actions().get(1).get("project")).isEqualTo(callers.get("project"));
        assertThat(page.episodes()).singleElement().satisfies(e -> assertThat(e)
                .containsEntry("title", "Adding org.postgresql:postgresql"));
    }

    @Test
    void a_call_that_throws_is_logged_as_failed_and_still_throws() {
        McpContext ctx = context();
        McpConnection connection = ctx.connections().open("claude-code", null, McpRevision.LATEST, McpElicitModes.NONE);
        String id = log.open("mcp", "claude-code", connection.label(), project.toString());
        McpTools tools = new McpTools(List.of(tool("query", in -> {
            throw new McpError(-32602, "requires arguments.question");
        })));

        assertThatThrownBy(() -> tools.call(ctx, call("query", Map.of("dir", project.toString())), connection, null))
                .isInstanceOf(McpError.class);
        assertThat(log.session(id, 0, 10).orElseThrow().actions())
                .singleElement()
                .satisfies(a -> assertThat(a)
                        .containsEntry("outcome", "error")
                        .containsEntry("headline", "failed")
                        .containsEntry("summary", "query \"\""));
    }

    private McpContext context() {
        McpContext ctx = new McpContext(
                () -> new StatusSnapshot("0", 1L, 0L, 0, 0, 1L, 1L, 1L, -1L, 1, 1L),
                new EngineHttpJobs() {
                    @Override
                    public long trigger(JobSpec spec) {
                        return 1L;
                    }

                    @Override
                    public boolean cancel(long requestId) {
                        return false;
                    }

                    @Override
                    public int cancelDir(String dir) {
                        return 0;
                    }
                },
                dir -> Map.of(),
                List::of,
                "0",
                null,
                List::of,
                null,
                null);
        ctx.sessions(log);
        return ctx;
    }

    private static Map<String, Object> call(String name, Map<String, Object> arguments) {
        return Map.of("name", name, "arguments", arguments);
    }

    private static McpTool tool(String name, Function<McpCall, Map<String, Object>> body) {
        return new McpTool() {
            @Override
            public Spec spec() {
                return new Spec(name, name, McpSchemas.object(Map.of()));
            }

            @Override
            public Map<String, Object> call(McpCall in) {
                return body.apply(in);
            }
        };
    }
}
