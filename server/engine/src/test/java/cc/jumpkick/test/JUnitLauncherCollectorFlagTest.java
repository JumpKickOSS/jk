// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.JvmOptions;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every test JVM runs the JVM's default collector on every core: jk plans no {@code
 * -XX:ActiveProcessorCount} and no {@code -XX:+Use…GC}, and only the module's own {@code [test]
 * jvm-args} choose either. A heap pinned there is the heap.
 */
class JUnitLauncherCollectorFlagTest {

    @BeforeEach
    void noSharedPlan() {
        JvmOptions.resetSharedPlanForTests();
    }

    private static boolean collectorFlag(String flag) {
        return flag.startsWith("-XX:+Use") && flag.endsWith("GC");
    }

    @Test
    void a_planned_test_jvm_names_no_cpu_count_and_no_collector() {
        JUnitLauncher launcher = new JUnitLauncher();
        for (JvmRole role : JvmRole.values()) {
            assertThat(launcher.jvmFlags(role, 8, null))
                    .as(role.name())
                    .noneMatch(f -> f.startsWith("-XX:ActiveProcessorCount"))
                    .noneMatch(JUnitLauncherCollectorFlagTest::collectorFlag);
        }
    }

    @Test
    void the_modules_own_cpu_count_and_collector_pass_through() {
        JUnitLauncher launcher =
                new JUnitLauncher().withJvmArgs(List.of("-XX:ActiveProcessorCount=3", "-XX:+UseParallelGC"));
        for (JvmRole role : JvmRole.values()) {
            List<String> flags = launcher.jvmFlags(role, 8, null);
            assertThat(flags).as(role.name()).containsOnlyOnce("-XX:ActiveProcessorCount=3", "-XX:+UseParallelGC");
            assertThat(flags.stream().filter(f -> f.startsWith("-XX:ActiveProcessorCount")))
                    .as(role.name())
                    .hasSize(1);
            assertThat(flags.stream().filter(JUnitLauncherCollectorFlagTest::collectorFlag))
                    .as(role.name())
                    .hasSize(1);
        }
    }

    @Test
    void a_heap_in_the_modules_jvm_args_is_the_only_heap_flag() {
        JUnitLauncher launcher = new JUnitLauncher().withJvmArgs(List.of("-Xmx6g"));
        for (JvmRole role : JvmRole.values()) {
            assertThat(launcher.jvmFlags(role, 1, null).stream().filter(JvmOptions::pinsHeap))
                    .as(role.name())
                    .containsExactly("-Xmx6g");
            assertThat(launcher.jvmFlags(role, 1, null))
                    .as(role.name())
                    .noneMatch(f -> f.startsWith("-XX:MaxRAMPercentage"));
        }
    }
}
