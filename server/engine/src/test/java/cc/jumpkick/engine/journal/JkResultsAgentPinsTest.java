// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.JumpKickPins;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The agent report names JumpKick's own pins behind the running jk, as the console does. */
class JkResultsAgentPinsTest {

    /** An agent hears that JumpKick's own pins are behind the running jk on a green run too. */
    @Test
    void an_ok_run_names_jumpkick_pins_behind_the_running_jk() {
        BuildRecord.Diag behind = new BuildRecord.Diag(
                "warning",
                "/ws/rest-service",
                "lock",
                JumpKickPins.BEHIND,
                "JumpKick's own pin is behind this jk (0.51.2): library 0.50.2 (jk-guards.toml); …",
                null,
                null);
        BuildRecord r = JkResultsAgentTest.record(
                "test", true, false, 500, new BuildRecord.Tests(1, 1, 0, 0), List.of(behind), List.of());
        assertThat(JkResultsAgent.render(r)).isEqualTo("""
                OK test rest-service · 1 test · 500ms
                JumpKick's own pin is behind this jk (0.51.2): library 0.50.2 (jk-guards.toml); …
                """);
    }
}
