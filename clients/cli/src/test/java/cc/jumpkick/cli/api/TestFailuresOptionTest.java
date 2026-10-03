// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.TestFailureMode;
import cc.jumpkick.model.command.Invocation;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code --test-failures} wins over {@code JK_TEST_FAILURES}; neither leaves each module's own. */
class TestFailuresOptionTest {

    private static final Invocation NO_FLAG = Invocation.builder().build();

    private static Invocation flag(String mode) {
        return Invocation.builder().putValue("test-failures", mode).build();
    }

    @Test
    void the_flag_wins_over_the_environment() {
        Map<String, String> env = Map.of(CommonOpts.TEST_FAILURES_ENV, "report");
        assertThat(CommonOpts.testFailuresValue(flag("fail"), env::get)).isEqualTo(TestFailureMode.FAIL);
        assertThat(CommonOpts.testFailuresValue(NO_FLAG, env::get)).isEqualTo(TestFailureMode.REPORT);
    }

    @Test
    void neither_leaves_the_modules_own_mode() {
        assertThat(CommonOpts.testFailuresValue(NO_FLAG, Map.<String, String>of()::get))
                .isNull();
    }

    @Test
    void a_mode_that_is_neither_names_its_source() {
        assertThatThrownBy(() -> CommonOpts.testFailuresValue(flag("ignore"), Map.<String, String>of()::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--test-failures must be fail or report (got `ignore`)");
        Map<String, String> env = Map.of(CommonOpts.TEST_FAILURES_ENV, "x");
        assertThatThrownBy(() -> CommonOpts.testFailuresValue(NO_FLAG, env::get))
                .hasMessageStartingWith(CommonOpts.TEST_FAILURES_ENV + " must be");
    }
}
