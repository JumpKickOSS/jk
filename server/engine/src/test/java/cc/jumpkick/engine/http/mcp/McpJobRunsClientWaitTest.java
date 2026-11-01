// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** A host that gives up on a request after a minute gets its answer, and keeps its job, before then. */
class McpJobRunsClientWaitTest {

    @SuppressWarnings("NullAway")
    private static McpCall call(String client, Map<String, Object> args) {
        return new McpCall(null, args, null, new McpConnection("c1", client));
    }

    @Test
    void a_short_timeout_host_parks_under_its_limit_unless_it_asks_for_more() {
        assertThat(McpJobRuns.timeoutS(call("Cursor", Map.of()))).isEqualTo(McpJobRuns.SHORT_CLIENT_WAIT_S);
        assertThat(McpJobRuns.timeoutS(call("Cursor", Map.of("timeout_s", 300))))
                .isEqualTo(300);
        assertThat(McpJobRuns.timeoutS(call("claude-code", Map.of()))).isEqualTo(McpJobRuns.DEFAULT_WAIT_S);
    }
}
