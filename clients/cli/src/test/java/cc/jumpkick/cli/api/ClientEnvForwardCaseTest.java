// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Windows variable names are case-insensitive: a shell that spells a declared name {@code My_Token}
 * still forwards it for {@code [env] require = ["MY_TOKEN"]}. The platform is passed, so this runs
 * on any host; the engine's side of the match ({@code RequestShell}) needs a Windows host.
 */
class ClientEnvForwardCaseTest {

    private static final String SPELLED = "My_Case_Probe_Token";

    @AfterEach
    void clear() {
        System.clearProperty("jk.env." + SPELLED);
    }

    @Test
    void a_declared_name_spelled_in_another_case_is_forwarded_on_windows() {
        System.setProperty("jk.env." + SPELLED, "abc");

        assertThat(ClientEnvForward.forwarded(List.of("MY_CASE_PROBE_TOKEN"), true))
                .containsEntry(SPELLED, "abc");
        assertThat(ClientEnvForward.forwarded(List.of("MY_CASE_PROBE_TOKEN"), false))
                .as("elsewhere it is another variable")
                .doesNotContainKey(SPELLED);
    }
}
