// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An engine's Windows starts go through the distro's interop server, whatever socket the session
 * that started the engine left behind: that session's server stops starting Windows programs once
 * the session ends, and a start through it hangs.
 */
class WslInteropTest {

    @Test
    void a_start_names_the_distros_server_over_the_sessions_one() {
        Map<String, String> env = new HashMap<>(Map.of("WSL_INTEROP", "/run/WSL/390210_interop"));
        assertThat(WslInterop.routed(env, p -> true)).containsEntry("WSL_INTEROP", "/run/WSL/1_interop");
        assertThat(WslInterop.routed(new HashMap<>(), p -> true))
                .as("a daemon with no WSL_INTEROP of its own")
                .containsEntry("WSL_INTEROP", "/run/WSL/1_interop");
    }

    @Test
    void a_distro_without_its_own_server_is_left_as_it_is() {
        Map<String, String> env = new HashMap<>(Map.of("WSL_INTEROP", "/run/WSL/7_interop"));
        assertThat(WslInterop.routed(env, p -> false)).containsEntry("WSL_INTEROP", "/run/WSL/7_interop");
        assertThat(WslInterop.server(p -> p.equals("/run/WSL/1_interop"))).isEqualTo("/run/WSL/1_interop");
    }
}
