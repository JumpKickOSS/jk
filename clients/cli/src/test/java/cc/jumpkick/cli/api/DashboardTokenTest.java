// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.DashboardSigning;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A printed dashboard link carries the engine's token only for a person at a terminal. */
class DashboardTokenTest {

    private static Function<String, @Nullable String> env(Map<String, String> vars) {
        return vars::get;
    }

    @Test
    void a_person_at_an_interactive_terminal_gets_the_signed_link() {
        assertThat(DashboardToken.mayPrint(false, false, true, env(Map.of()))).isTrue();
    }

    @Test
    void piped_agent_json_ci_and_agent_spawned_output_never_carries_the_token() {
        assertThat(DashboardToken.mayPrint(false, false, false, env(Map.of())))
                .as("piped")
                .isFalse();
        assertThat(DashboardToken.mayPrint(true, false, true, env(Map.of())))
                .as("--agent")
                .isFalse();
        assertThat(DashboardToken.mayPrint(false, true, true, env(Map.of())))
                .as("-O json")
                .isFalse();
        assertThat(DashboardToken.mayPrint(false, false, true, env(Map.of("CI", "true"))))
                .as("CI")
                .isFalse();
        for (String agent : AgentMode.SPAWNED) {
            assertThat(DashboardToken.mayPrint(false, false, true, env(Map.of(agent, "1"))))
                    .as(agent)
                    .isFalse();
        }
        assertThat(DashboardToken.mayPrint(false, false, true, env(Map.of("CI", "false"))))
                .as("CI=false is not CI")
                .isTrue();
    }

    @Test
    void the_signing_follows_the_routes_the_dashboard_adopts_a_token_from() {
        assertThat(DashboardSigning.signed("http://127.0.0.1:8910/", "T")).isEqualTo("http://127.0.0.1:8910/#t=T");
        assertThat(DashboardSigning.signed("http://127.0.0.1:8910/#decision/d1", "T"))
                .isEqualTo("http://127.0.0.1:8910/#decision/d1?t=T");
        assertThat(DashboardSigning.signed("http://127.0.0.1:8910/#new?name=a", "T"))
                .isEqualTo("http://127.0.0.1:8910/#new?name=a&t=T");
        assertThat(DashboardSigning.signed("http://127.0.0.1:8910/", "")).isEqualTo("http://127.0.0.1:8910/");
    }
}
