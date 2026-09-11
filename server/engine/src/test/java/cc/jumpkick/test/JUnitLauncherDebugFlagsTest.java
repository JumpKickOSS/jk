// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.DebugJvm;
import cc.jumpkick.engine.plugin.JvmOptions;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The JDWP agent belongs to the one JVM that runs the tests. The launcher forks three kinds of JVM
 * — discovery, pull-mode shard workers, and the single suite runner — and only the last one's
 * flag list may carry it; the shared worker flags every other jk fork starts from never do.
 */
class JUnitLauncherDebugFlagsTest {

    private static final DebugJvm DEBUG = DebugJvm.parse("localhost:5005");

    @Test
    void only_the_suite_jvm_carries_the_agent() {
        JUnitLauncher launcher = new JUnitLauncher().withDebug(DEBUG);

        assertThat(launcher.jvmFlags(JUnitLauncher.JvmRole.SUITE, 1, null)).contains(DEBUG.agentArg());
        assertThat(launcher.jvmFlags(JUnitLauncher.JvmRole.DISCOVERY, 1, null))
                .noneMatch(JUnitLauncherDebugFlagsTest::jdwp);
        assertThat(launcher.jvmFlags(JUnitLauncher.JvmRole.PULL_WORKER, 4, null))
                .noneMatch(JUnitLauncherDebugFlagsTest::jdwp);
    }

    @Test
    void the_agent_is_added_once_and_leaves_the_runner_flags_intact() {
        List<String> plain = new JUnitLauncher().jvmFlags(JUnitLauncher.JvmRole.SUITE, 1, null);
        List<String> debugged = new JUnitLauncher().withDebug(DEBUG).jvmFlags(JUnitLauncher.JvmRole.SUITE, 1, null);

        assertThat(plain).noneMatch(JUnitLauncherDebugFlagsTest::jdwp);
        assertThat(debugged).hasSize(plain.size() + 1).containsAll(plain);
        assertThat(debugged.stream().filter(JUnitLauncherDebugFlagsTest::jdwp)).hasSize(1);
    }

    @Test
    void no_debug_request_means_no_agent_anywhere() {
        JUnitLauncher launcher = new JUnitLauncher().withDebug(null);
        for (JUnitLauncher.JvmRole role : JUnitLauncher.JvmRole.values()) {
            assertThat(launcher.jvmFlags(role, 2, null)).as(role.name()).noneMatch(JUnitLauncherDebugFlagsTest::jdwp);
        }
    }

    /** The worker flags every jk-forked JVM starts from — compilers, plugin tools — know nothing of JDWP. */
    @Test
    void the_shared_worker_flags_never_carry_jdwp() {
        assertThat(JvmOptions.workerFlags(1)).noneMatch(JUnitLauncherDebugFlagsTest::jdwp);
        assertThat(JvmOptions.batchFlags(2)).noneMatch(JUnitLauncherDebugFlagsTest::jdwp);
    }

    private static boolean jdwp(String flag) {
        return flag.contains("jdwp");
    }
}
