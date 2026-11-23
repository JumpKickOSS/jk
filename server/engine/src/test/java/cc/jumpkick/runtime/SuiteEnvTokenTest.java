// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.base.TestEnv;
import cc.jumpkick.task.ClasspathFingerprint;
import cc.jumpkick.task.KeyContract;
import cc.jumpkick.util.TestHomes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A run-tests stamp keys what every suite JVM gets from jk on any host by value: the variable names
 * and constant values of the sandbox, what a pooled worker's environment changes and which trees warm
 * the sandbox store. The token is built by the functions the launch builds the environment with, so
 * an edit to one of those constants moves it, and each host's directories are stand-ins, so it holds
 * no path.
 */
class SuiteEnvTokenTest {

    @AfterEach
    void forget() {
        KeyContract.SUITE.overrideDigestForTests(null);
    }

    /** The constants with the real roots are what the launch sets: one function builds both. */
    @Test
    void the_constants_are_what_the_launch_sets(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("m");
        JkBuild project = module(dir, "");
        Map<String, String> launched = TestEnv.forModuleUnseeded(project, dir, BuildLayout.of(dir, project))
                .extras();

        Map<String, String> constants = TestEnv.constants(
                project.build().testJvm(),
                TestHomes.pathFor(dir),
                TestHomes.tmpFor(dir),
                Path.of(System.getProperty("user.home")));

        assertThat(constants).isNotEmpty();
        constants.forEach((name, value) -> assertThat(launched).containsEntry(name, value));
    }

    @Test
    void the_token_spells_each_name_and_constant(@TempDir Path tmp) throws Exception {
        String token = SuiteEnvKeys.environment(module(tmp.resolve("m"), ""));

        assertThat(token)
                .contains("TERM=dumb")
                .contains("JK_HOME=@home ")
                .contains("JK_STATE_DIR=@home/state")
                .contains("JK_M2_LOCAL=@home/store/repos/central")
                .contains("TMPDIR=@tmp")
                .contains("JK_HTTP_PORT=0")
                .contains("GIT_CONFIG_NOSYSTEM=1")
                .contains("GIT_CONFIG_GLOBAL=@home/gitconfig")
                .contains("TZ=UTC")
                .contains("worker:JK_HOME=@home/w1")
                .contains("worker:JK_STATE_DIR=@home/state/w1")
                .contains("worker:TMPDIR=@worker-tmp")
                .contains("seed:pool=repos/central")
                .contains("trees=org/junit,");
    }

    @Test
    void paths_are_not_in_the_token(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("m");
        String token = SuiteEnvKeys.environment(module(dir, ""));

        assertThat(token)
                .doesNotContain(tmp.toString())
                .doesNotContain(TestHomes.pathFor(dir).toString())
                .doesNotContain(TestHomes.tmpFor(dir).toString())
                .doesNotContain(System.getProperty("user.home"))
                .doesNotContain("\\");
        assertThat(SuiteEnvKeys.environment(module(tmp.resolve("elsewhere"), "")))
                .isEqualTo(token);
    }

    /** A constant the module sets moves the token; the suite code's digest moving does not. */
    @Test
    void the_token_moves_with_a_constant_and_names_no_code(@TempDir Path tmp) throws Exception {
        JkBuild utc = module(tmp.resolve("utc"), "");
        KeyContract.SUITE.overrideDigestForTests("suites000001");
        String one = SuiteEnvKeys.environment(utc);
        KeyContract.SUITE.overrideDigestForTests("suites000002");

        assertThat(SuiteEnvKeys.environment(utc)).isEqualTo(one);
        assertThat(SuiteEnvKeys.environment(module(tmp.resolve("paris"), """
                        [test]
                        timezone = "Europe/Paris"
                        """)))
                .contains("TZ=Europe/Paris")
                .isNotEqualTo(one);
    }

    @Test
    void the_stamp_carries_the_token(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("m");
        JkBuild project = module(dir, "");

        List<String> extras = TestStampInputs.of(TestStampInputs.Facts.of(dir, project), ClasspathFingerprint.ON_DISK)
                .extras();

        assertThat(extras.stream().filter(token -> token.startsWith("suite-env:")))
                .containsExactly(SuiteEnvKeys.environment(project));
    }

    private static JkBuild module(Path dir, String test) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "m"
                version = "1.0.0"
                """ + test);
        return JkBuildParser.parse(dir.resolve("jk.toml"));
    }
}
