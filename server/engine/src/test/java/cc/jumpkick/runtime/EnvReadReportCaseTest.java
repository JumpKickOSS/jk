// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.WorkerEnv;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * On Windows a test that reads a declared variable in another case reads a declared variable, so
 * the env-read report does not call it undeclared. The platform is passed, so this runs anywhere.
 */
class EnvReadReportCaseTest {

    @Test
    void a_declared_name_read_in_another_case_reaches_the_suite_on_windows() {
        assertThat(EnvReadReport.reaches("my_token", Set.of("MY_TOKEN"), WorkerEnv.strict(), true))
                .isTrue();
        assertThat(EnvReadReport.reaches(
                        "my_token", Set.of(), WorkerEnv.strict().with(Map.of("MY_TOKEN", "x")), true))
                .isTrue();
    }

    @Test
    void elsewhere_the_case_is_part_of_the_name() {
        assertThat(EnvReadReport.reaches("my_token", Set.of("MY_TOKEN"), WorkerEnv.strict(), false))
                .isFalse();
    }
}
