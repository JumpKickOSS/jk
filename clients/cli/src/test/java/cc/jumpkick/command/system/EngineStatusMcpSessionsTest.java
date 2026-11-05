// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.jsonl.Jsonl;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code jk engine status} says how close the MCP connection table is to {@code [mcp] max-sessions}. */
class EngineStatusMcpSessionsTest {

    @SuppressWarnings("NullAway") // the absent vitals are the engine's own nulls
    private static EngineProbe.Status serving(int max, int loaded, int active) {
        return new EngineProbe.Status(
                "0.13.0",
                19518L,
                1_700_000_000_000L,
                2,
                0,
                false,
                0L,
                0L,
                0L,
                0L,
                "http://127.0.0.1:8910/",
                null,
                "http://127.0.0.1:8910/mcp",
                null,
                -1,
                -1L,
                -1L,
                -1.0,
                -1.0,
                "epoch-1",
                -1L,
                -1L,
                -1L,
                null,
                0,
                null,
                null,
                null,
                -1L,
                -1L,
                -1L,
                -1L,
                0L,
                0,
                null,
                -1,
                -1,
                false,
                null,
                null,
                max,
                loaded,
                active,
                -1L,
                List.of());
    }

    @Test
    void the_text_names_the_loaded_and_active_connections_against_the_limit() {
        assertThat(EngineStatusCommand.describeMcpSessions(serving(256, 3, 1))).isEqualTo("3 loaded of 256, 1 active");
    }

    @Test
    void json_carries_the_limit_and_both_counts() {
        String json = EngineStatusCommand.runningJson(serving(256, 3, 1), 60, List.of(), "auto");
        assertThat(Jsonl.intValue(json, "mcpMaxSessions", -1)).isEqualTo(256);
        assertThat(Jsonl.intValue(json, "mcpLoadedConnections", -1)).isEqualTo(3);
        assertThat(Jsonl.intValue(json, "mcpActiveConnections", -1)).isEqualTo(1);
    }
}
