// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.DebugJvm;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.model.JkBuild;
import org.junit.jupiter.api.Test;

/**
 * The one rule the run and the forecast both read for "this suite runs whatever its last green
 * marker says": a rebuild, a debug JVM, or a coverage run (asked for, or the module's own).
 */
class PlannerTestForcedRunTest {

    private static final String TOML = """
            group = "g"
            name = "app"
            version = "1.0"
            java = 25
            """;

    @Test
    void rebuild_debug_and_coverage_each_force_a_run_and_nothing_else_does() throws Exception {
        JkBuild project = JkBuildParser.parse(TOML);
        Session plain = Session.defaults();
        assertThat(SuiteRunRules.forcedRunReason(plain, project)).isNull();
        assertThat(SuiteRunRules.forcedRunReason(
                        plain.withConfig(JkConfig.empty().withRebuild(true)), project))
                .isEqualTo("rebuild");
        assertThat(SuiteRunRules.forcedRunReason(plain.withDebugJvm(new DebugJvm("localhost", 5005, false)), project))
                .isEqualTo("debug");
        assertThat(SuiteRunRules.forcedRunReason(plain.withCoverage(true), project))
                .isEqualTo("coverage");
    }

    @Test
    void a_module_that_always_measures_coverage_always_runs() throws Exception {
        JkBuild project = JkBuildParser.parse(TOML + "\n[test]\ncoverage = true\n");
        assertThat(SuiteRunRules.forcedRunReason(Session.defaults(), project)).isEqualTo("coverage");
    }
}
