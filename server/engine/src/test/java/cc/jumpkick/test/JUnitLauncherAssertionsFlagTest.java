// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.JvmOptions;
import org.junit.jupiter.api.Test;

/**
 * Every JVM the launcher forks — discovery, pull workers, the single suite runner — starts with
 * {@code -ea}, as Surefire's and Gradle's test JVMs do; {@code [test] assertions = false} removes it
 * from all three. The shared worker flags other jk forks start from never carry it.
 */
class JUnitLauncherAssertionsFlagTest {

    @Test
    void every_test_jvm_runs_with_assertions_on_by_default() {
        JUnitLauncher launcher = new JUnitLauncher();
        for (JvmRole role : JvmRole.values()) {
            assertThat(launcher.jvmFlags(role, 2, null)).as(role.name()).containsOnlyOnce("-ea");
        }
    }

    @Test
    void the_module_opt_out_removes_the_flag_from_every_role() {
        JUnitLauncher launcher = new JUnitLauncher().withAssertions(false);
        for (JvmRole role : JvmRole.values()) {
            assertThat(launcher.jvmFlags(role, 2, null)).as(role.name()).doesNotContain("-ea");
        }
    }

    @Test
    void the_shared_worker_flags_never_carry_it() {
        assertThat(JvmOptions.workerFlags(1)).doesNotContain("-ea");
        assertThat(JvmOptions.batchFlags(2)).doesNotContain("-ea");
    }
}
