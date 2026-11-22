// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.engine.http.api.EngineHttpJobs;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpConnection;
import cc.jumpkick.engine.http.mcp.McpContext;
import cc.jumpkick.engine.http.mcp.McpError;
import cc.jumpkick.engine.http.mcp.McpJobRuns;
import cc.jumpkick.engine.http.mcp.tools.McpTools;
import cc.jumpkick.engine.jobs.JobOrigin;
import cc.jumpkick.engine.jobs.JobRequests;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.jsonl.MiniJson;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * One job request through {@code POST /api/build} and MCP {@code run}: the same JSON admits the
 * same {@link JobSpec} on both, apart from who asked, and a field neither knows is refused by name.
 */
@Tag("integration")
class JobRequestSurfacesTest extends HttpEngineServerHarness {

    private static final List<String> BODIES = List.of(
            "{\"dir\":\"/ws\"}",
            "{\"dir\":\"/ws\",\"kind\":\"test\",\"classes\":[\"FooTest\"]}",
            "{\"dir\":\"/ws\",\"kind\":\"test\",\"modules\":[\"core\",\"app\"],\"suites\":[\"integration\"]}",
            "{\"dir\":\"/ws\",\"kind\":\"test\",\"include_tags\":[\"fast\"],\"exclude_tags\":[\"slow\"]}",
            "{\"dir\":\"/ws\",\"kind\":\"build\",\"affected\":true,\"skip_tests\":true,\"deadline_s\":90}",
            "{\"dir\":\"/ws\",\"kind\":\"lock\",\"modules\":\"core,app\",\"classes\":\"A,B\"}",
            "{\"dir\":\"/ws\",\"kind\":\"update\",\"deps\":[\"jackson\"],\"major\":true}");

    private final List<JobSpec> mcpAdmitted = new ArrayList<>();

    @Test
    void the_same_body_admits_the_same_spec_on_rest_and_mcp() throws Exception {
        McpContext ctx = mcpContext();
        McpConnection connection = new McpConnection("c1", "claude-code");
        for (String body : BODIES) {
            assertThat(postBuild(body).statusCode()).as(body).isEqualTo(202);
            @SuppressWarnings("unchecked")
            Map<String, Object> args = new LinkedHashMap<>((Map<String, Object>) MiniJson.parse(body));
            args.putIfAbsent("kind", "build");
            args.put("wait", false);
            McpJobRuns.run(new McpCall(ctx, args, null, connection), null);
        }
        assertThat(triggeredSpecs).hasSize(BODIES.size()).allSatisfy(s -> assertThat(s.origin())
                .isEqualTo(JobOrigin.WEB));
        assertThat(mcpAdmitted).hasSize(BODIES.size()).allSatisfy(s -> assertThat(s.origin())
                .isEqualTo(JobOrigin.mcp("claude-code c1")));
        for (int i = 0; i < BODIES.size(); i++) {
            assertThat(withWebOrigin(mcpAdmitted.get(i))).as(BODIES.get(i)).isEqualTo(triggeredSpecs.get(i));
        }
        JobSpec selected = triggeredSpecs.get(1);
        assertThat(selected.kind()).isEqualTo("test");
        assertThat(selected.classes()).containsExactly("FooTest");
        assertThat(triggeredSpecs.get(2).modules()).containsExactly("core", "app");
        assertThat(triggeredSpecs.get(2).suites()).containsExactly("integration");
        assertThat(triggeredSpecs.get(3).includeTags()).containsExactly("fast");
        assertThat(triggeredSpecs.get(3).excludeTags()).containsExactly("slow");
        assertThat(triggeredSpecs.get(4).modules()).containsExactly("affected-wip");
        assertThat(triggeredSpecs.get(4).skipTests()).isTrue();
        assertThat(triggeredSpecs.get(4).deadlineMs()).isEqualTo(90_000L);
        assertThat(triggeredSpecs.get(6).deps()).containsExactly("jackson");
        assertThat(triggeredSpecs.get(6).major()).isTrue();
    }

    @Test
    void an_unknown_field_is_refused_by_name_on_both_surfaces() throws Exception {
        HttpResponse<String> rest = postBuild("{\"dir\":\"/ws\",\"kind\":\"test\",\"clases\":[\"FooTest\"]}");
        assertThat(rest.statusCode()).isEqualTo(400);
        assertThat(rest.body()).contains("unknown field clases");
        assertThat(triggeredSpecs).isEmpty();
        McpContext ctx = mcpContext();
        assertThatThrownBy(() -> McpJobRuns.run(
                        new McpCall(
                                ctx,
                                Map.of("dir", "/ws", "kind", "test", "clases", List.of("FooTest"), "wait", false),
                                null),
                        null))
                .isInstanceOf(McpError.class)
                .hasMessageContaining("unknown field clases");
        assertThat(mcpAdmitted).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void every_argument_builds_card_names_is_one_the_decoder_accepts() {
        Map<String, Object> props =
                (Map<String, Object>) Objects.requireNonNull(McpTools.standard().tool("build"))
                        .spec()
                        .inputSchema()
                        .get("properties");
        List<String> accepted = new ArrayList<>(McpJobRuns.RUN_FIELDS);
        for (JobRequests.Field f : JobRequests.FIELDS) accepted.add(f.name());
        assertThat(accepted).containsAll(Objects.requireNonNull(props).keySet());
        assertThat(props).containsKeys("kind", "dir", "modules", "classes", "suites", "jid", "id");
    }

    private static JobSpec withWebOrigin(JobSpec s) {
        return new JobSpec(
                s.kind(),
                s.dir(),
                s.modules(),
                s.includeTags(),
                s.excludeTags(),
                s.suites(),
                s.classes(),
                s.skipTests(),
                s.affected(),
                s.deadlineMs(),
                s.deps(),
                s.major(),
                JobOrigin.WEB);
    }

    private McpContext mcpContext() {
        return new McpContext(
                () -> SNAPSHOT,
                new EngineHttpJobs() {
                    @Override
                    public long trigger(JobSpec spec) {
                        mcpAdmitted.add(spec);
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
    }
}
