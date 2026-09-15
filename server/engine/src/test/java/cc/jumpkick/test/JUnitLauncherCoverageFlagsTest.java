// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The JaCoCo agent belongs to every JVM that runs tests — the single suite runner and each
 * pull-mode shard — and to no JVM that only lists them. Every shard appends to the module's one
 * execution file, which is how a sharded module still yields one report.
 */
class JUnitLauncherCoverageFlagsTest {

    private static final CoverageAgent AGENT =
            new CoverageAgent(Path.of("/store/jacocoagent.jar"), Path.of("/w/target/reports/jacoco.exec"));

    @Test
    void every_test_running_jvm_carries_the_agent_and_discovery_does_not() {
        JUnitLauncher launcher = new JUnitLauncher().withCoverage(AGENT);
        assertThat(launcher.jvmFlags(JvmRole.SUITE, 1, null)).contains(AGENT.agentArg());
        assertThat(launcher.jvmFlags(JvmRole.PULL_WORKER, 4, null)).contains(AGENT.agentArg());
        assertThat(launcher.jvmFlags(JvmRole.DISCOVERY, 1, null)).noneMatch(f -> f.contains("jacoco"));
    }

    @Test
    void the_agent_appends_to_the_module_s_one_execution_file() {
        assertThat(AGENT.agentArg())
                .startsWith("-javaagent:" + Path.of("/store/jacocoagent.jar") + "=")
                .contains("destfile=" + Path.of("/w/target/reports/jacoco.exec"))
                .contains("append=true");
    }

    @Test
    void no_coverage_request_means_no_agent_anywhere() {
        JUnitLauncher launcher = new JUnitLauncher().withCoverage(null);
        for (JvmRole role : JvmRole.values()) {
            assertThat(launcher.jvmFlags(role, 2, null)).as(role.name()).noneMatch(f -> f.contains("jacoco"));
        }
    }
}
