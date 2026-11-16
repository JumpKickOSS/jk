// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Only the person's own engine takes part in the front door; a sandbox, private home or CI opts in or not at all. */
class FrontDoorScopeTest {

    private static final Path HOME = Path.of("/home/ada/.jk");
    private static final Path SANDBOX = Path.of("/home/ada/.jk/test-homes/0123456789ab");

    @Test
    void the_persons_own_engine_takes_part() {
        assertThat(FrontDoorScope.active(Map.<String, String>of()::get, HOME)).isTrue();
    }

    @Test
    void a_test_sandbox_a_private_home_and_ci_take_no_part() {
        assertThat(FrontDoorScope.active(Map.<String, String>of()::get, SANDBOX))
                .isFalse();
        assertThat(FrontDoorScope.active(Map.of("JK_HOME", "/tmp/scratch")::get, Path.of("/tmp/scratch")))
                .isFalse();
        assertThat(FrontDoorScope.active(Map.of("CI", "true")::get, HOME)).isFalse();
    }

    @Test
    void the_variable_opts_in_or_out_whatever_the_home() {
        assertThat(FrontDoorScope.active(Map.of("JK_FRONT_DOOR", "true", "JK_HOME", "/x")::get, SANDBOX))
                .isTrue();
        assertThat(FrontDoorScope.active(Map.of("JK_FRONT_DOOR", "false")::get, HOME))
                .isFalse();
    }
}
