// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.interop;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.command.interop.McpCommand.Reach;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Which engine a {@code jk mcp} bridge reaches after jk updated itself while it ran: never its own
 * older engine, which would displace the newer release (or fail, its jar gone), but the newer one
 * serving, or the one the installed client starts.
 */
class McpCommandReachTest {

    private static final Optional<String> INSTALLED = Optional.of("0.52.0");

    @Test
    void the_newer_engine_serving_is_used_as_it_is() {
        assertThat(McpCommand.reach("0.52.0", false, "0.51.0", true, INSTALLED)).isEqualTo(Reach.LIVE_NEWER);
    }

    @Test
    void with_the_newer_engine_stopped_or_draining_the_installed_client_starts_it() {
        assertThat(McpCommand.reach(null, false, "0.51.0", true, INSTALLED)).isEqualTo(Reach.START_INSTALLED);
        assertThat(McpCommand.reach("0.52.0", true, "0.51.0", true, INSTALLED)).isEqualTo(Reach.START_INSTALLED);
        assertThat(McpCommand.reach("0.51.0", false, "0.51.0", true, INSTALLED))
                .as("the bridge's own older engine, still answering")
                .isEqualTo(Reach.START_INSTALLED);
    }

    @Test
    void a_current_or_development_bridge_reaches_its_own_engine() {
        assertThat(McpCommand.reach(null, false, "0.52.0", true, INSTALLED)).isEqualTo(Reach.OWN);
        assertThat(McpCommand.reach(null, false, "0.51.0", false, INSTALLED)).isEqualTo(Reach.OWN);
        assertThat(McpCommand.reach(null, false, "0.52.0-SNAPSHOT", true, Optional.of("0.52.0-SNAPSHOT")))
                .isEqualTo(Reach.OWN);
        assertThat(McpCommand.reach(null, false, "0.51.0", true, Optional.empty()))
                .isEqualTo(Reach.OWN);
    }
}
