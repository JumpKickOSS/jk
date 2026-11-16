// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.base.TestEnv;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A test JVM runs in a fixed time zone, UTC unless the module's {@code [test] timezone} names
 * another, so neither the zone the engine was started in nor the caller's {@code TZ} decides what a
 * suite computes, and two hosts in two zones share its result (JK-9CSPY). Under {@code timezone =
 * "inherit"} the caller's {@code TZ} (the request's, not the engine's) reaches the JVM and keys the
 * suite.
 */
class SuiteTimezoneEnvTest {

    private static final Map<String, String> TOKYO = Map.of("PATH", "/bin", "TZ", "Asia/Tokyo");
    private static final Map<String, String> NEW_YORK = Map.of("PATH", "/bin", "TZ", "America/New_York");

    @TempDir
    Path tmp;

    @Test
    void by_default_the_suite_runs_and_keys_in_utc() throws Exception {
        JkBuild project = project("");
        assertThat(tokens(project, TOKYO))
                .as("two builds differing only in TZ take one stamp")
                .isEqualTo(tokens(project, NEW_YORK))
                .contains("test-timezone:UTC");
        assertThat(jvmEnv(project, TOKYO)).containsEntry("TZ", "UTC");
        List<String> flags = TestStampInputs.testJvmArgs(project, null);
        assertThat(flags).contains("-Duser.timezone=UTC");
        assertThat(flags.indexOf("-Duser.timezone=UTC"))
                .as("ahead of the module's flags")
                .isLessThan(3);
    }

    @Test
    void a_modules_own_flag_comes_after_and_wins() throws Exception {
        JkBuild project = project("[test]\njvm-args = [\"-Duser.timezone=Europe/Paris\"]\n");
        List<String> flags = TestStampInputs.testJvmArgs(project, null);
        assertThat(flags.indexOf("-Duser.timezone=Europe/Paris")).isGreaterThan(flags.indexOf("-Duser.timezone=UTC"));
    }

    @Test
    void a_declared_zone_keys_and_reaches_the_suite() throws Exception {
        JkBuild berlin = project("[test]\ntimezone = \"Europe/Berlin\"\n");
        assertThat(tokens(berlin, TOKYO)).contains("test-timezone:Europe/Berlin");
        assertThat(jvmEnv(berlin, TOKYO)).containsEntry("TZ", "Europe/Berlin");
        assertThat(TestStampInputs.testJvmArgs(berlin, null)).contains("-Duser.timezone=Europe/Berlin");
    }

    @Test
    void an_inherited_zone_is_the_callers_and_keys_the_suite_apart() throws Exception {
        JkBuild project = project("[test]\ntimezone = \"inherit\"\n");
        assertThat(tokens(project, TOKYO)).contains("test-timezone:inherit=Asia/Tokyo");
        assertThat(tokens(project, TOKYO)).isNotEqualTo(tokens(project, NEW_YORK));
        assertThat(jvmEnv(project, TOKYO)).containsEntry("TZ", "Asia/Tokyo");
        assertThat(TestStampInputs.testJvmArgs(project, null)).noneMatch(a -> a.startsWith("-Duser.timezone"));
    }

    @Test
    void a_zone_that_is_not_one_is_refused() {
        assertThatThrownBy(() -> project("[test]\ntimezone = \"Mars/Olympus\"\n"))
                .hasMessageContaining("[test].timezone must be a zone id");
    }

    private List<String> tokens(JkBuild project, Map<String, String> shell) throws Exception {
        return WorkerEnv.withEngineEnvironment(
                Map.of("PATH", "/engine/bin", "TZ", "Pacific/Auckland"),
                () -> RequestShell.where(
                        shell, () -> SuiteEnvKeys.tokens(project.build(), tmp, TestSelection.DEFAULT)));
    }

    private Map<String, String> jvmEnv(JkBuild project, Map<String, String> shell) throws Exception {
        return WorkerEnv.withEngineEnvironment(
                Map.of("PATH", "/engine/bin", "TZ", "Pacific/Auckland"),
                () -> RequestShell.where(
                        shell, () -> TestEnv.forModuleUnseeded(project, tmp, BuildLayout.of(tmp, project))
                                .environment()));
    }

    private JkBuild project(String tables) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group   = "com.example"
                name    = "m"
                version = "1.0.0"
                """ + tables);
        return JkBuildParser.parse(tmp.resolve("jk.toml"));
    }
}
