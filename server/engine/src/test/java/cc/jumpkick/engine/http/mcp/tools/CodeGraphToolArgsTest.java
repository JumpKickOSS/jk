// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp.tools;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.http.api.EngineHttpJobs;
import cc.jumpkick.engine.http.api.StatusSnapshot;
import cc.jumpkick.engine.http.mcp.McpCall;
import cc.jumpkick.engine.http.mcp.McpContext;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.wire.protocol.GraphRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A graph tool reads only the settings its own card declares; the others keep their defaults. */
class CodeGraphToolArgsTest {

    private static final Map<String, Object> EVERY_SETTING = Map.of(
            "address", "A.b", "to", "c", "token_budget", 50_000, "max_hops", 40, "scope", "src", "transitive", true);

    @Test
    void rename_reads_no_graph_setting_its_card_does_not_declare() {
        GraphRequest request = request("rename");

        assertThat(request.budget()).isEqualTo(2000);
        assertThat(request.maxHops()).isEqualTo(8);
        assertThat(request.scope()).isNull();
        assertThat(request.options()).doesNotContain("transitive=true");
    }

    @Test
    void usages_reads_the_settings_it_declares() {
        GraphRequest request = request("usages");

        assertThat(request.op()).isEqualTo("usages");

        assertThat(request.budget()).isEqualTo(50_000);
        assertThat(request.maxHops()).isEqualTo(40);
        assertThat(request.scope()).isEqualTo("src");
        assertThat(request.options()).contains("transitive=true");
    }

    @Test
    void delete_asks_the_delete_operation_with_its_kind_alone() {
        GraphRequest request = request("delete", Map.of("address", "Order.legacy", "kind", "method", "scope", "src"));

        assertThat(request.op()).isEqualTo("delete");
        assertThat(request.args()).containsExactly("Order.legacy");
        assertThat(request.options()).containsExactly("kind=method");
        assertThat(request.scope()).isNull();
    }

    @Test
    void move_asks_the_move_operation_with_the_member_and_its_target() {
        GraphRequest request = request("move", Map.of("address", "Order.join", "to", "Texts", "scope", "src"));

        assertThat(request.op()).isEqualTo("move");
        assertThat(request.args()).containsExactly("Order.join", "Texts");
        assertThat(request.options()).isEmpty();
        assertThat(request.scope()).isNull();
    }

    private static GraphRequest request(String tool) {
        return request(tool, EVERY_SETTING);
    }

    private static GraphRequest request(String tool, Map<String, Object> args) {
        CodeGraphTool graph = (CodeGraphTool) requireNonNull(McpTools.standard().tool(tool));
        return graph.request(new McpCall(context(), args, null), "/ws");
    }

    private static McpContext context() {
        return new McpContext(
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
    }
}
