// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.JvmOptions;
import org.junit.jupiter.api.Test;

/**
 * No JVM the launcher forks carries jk's {@code -Xss} reserve: a test thread gets the platform
 * default stack, as under Surefire and Gradle, so a recursive test that passes under Maven passes
 * here. The batch flags jk's compilers and plugin tools start from keep the reserve.
 */
class JUnitLauncherStackFlagTest {

    @Test
    void every_test_jvm_runs_on_the_platform_default_stack() {
        JUnitLauncher launcher = new JUnitLauncher();
        for (JvmRole role : JvmRole.values()) {
            assertThat(launcher.jvmFlags(role, 2, null)).as(role.name()).noneMatch(f -> f.startsWith("-Xss"));
        }
    }

    @Test
    void batch_workers_keep_the_smaller_reserve() {
        assertThat(JvmOptions.workerFlags(1)).contains("-Xss" + JvmOptions.DEFAULT_STACK_KB + "k");
        assertThat(JvmOptions.batchFlags(2)).contains("-Xss" + JvmOptions.DEFAULT_STACK_KB + "k");
    }
}
