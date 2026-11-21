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
    void callers_reads_the_settings_it_declares() {
        GraphRequest request = request("callers");

        assertThat(request.budget()).isEqualTo(50_000);
        assertThat(request.maxHops()).isEqualTo(40);
        assertThat(request.scope()).isEqualTo("src");
        assertThat(request.options()).contains("transitive=true");
    }

    private static GraphRequest request(String tool) {
        CodeGraphTool graph = (CodeGraphTool) requireNonNull(McpTools.standard().tool(tool));
        return graph.request(new McpCall(context(), EVERY_SETTING, null), "/ws");
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
