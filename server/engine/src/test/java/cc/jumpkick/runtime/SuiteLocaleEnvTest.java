// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
 * The locale variables a suite is keyed on are the caller's, the shell running {@code jk}, and so
 * are the ones its test JVM gets: {@code LC_TIME=de_DE.UTF-8 jk test} reaches the suite and its key.
 * The terminal is not: a test JVM's {@code TERM} is a fixed {@code dumb} and no other terminal
 * variable reaches it, so a suite started from another terminal keys and runs the same, unless the
 * module declares {@code TERM}.
 */
class SuiteLocaleEnvTest {

    private static final String LC_TIME = "de_DE.probe-locale";

    @TempDir
    Path tmp;

    @Test
    void a_callers_lc_time_keys_the_suite() throws Exception {
        JkBuild project = project();
        List<String> tokens = RequestShell.where(
                Map.of("PATH", "/bin", "LC_TIME", LC_TIME),
                () -> SuiteEnvKeys.tokens(project.build(), tmp, TestSelection.DEFAULT));
        assertThat(tokens).contains("machine-env:LC_TIME=" + LC_TIME);
    }

    @Test
    void a_callers_lc_time_reaches_the_test_jvm() throws Exception {
        JkBuild project = project();
        Map<String, String> env = WorkerEnv.withEngineEnvironment(
                Map.of("PATH", "/engine/bin"),
                () -> RequestShell.where(Map.of("PATH", "/bin", "LC_TIME", LC_TIME), () -> TestEnv.forModuleUnseeded(
                                project, tmp, BuildLayout.of(tmp, project))
                        .environment()));
        assertThat(env).containsEntry("LC_TIME", LC_TIME);
    }

    @Test
    void a_different_callers_terminal_keys_and_runs_the_suite_the_same() throws Exception {
        JkBuild project = project();
        Map<String, String> tmux = Map.of("PATH", "/bin", "TERM", "tmux-256color", "COLORTERM", "truecolor");
        Map<String, String> agent = Map.of("PATH", "/bin", "TERM", "dumb", "NO_COLOR", "1", "CLICOLOR_FORCE", "1");

        assertThat(tokens(project, tmux)).isEqualTo(tokens(project, agent));
        for (Map<String, String> shell : List.of(tmux, agent)) {
            assertThat(jvmEnv(project, shell))
                    .containsEntry("TERM", "dumb")
                    .doesNotContainKeys("COLORTERM", "NO_COLOR", "FORCE_COLOR", "CLICOLOR", "CLICOLOR_FORCE");
        }
    }

    @Test
    void a_different_callers_lang_keys_the_suite_apart() throws Exception {
        JkBuild project = project();

        assertThat(tokens(project, Map.of("PATH", "/bin", "LANG", "de_DE.UTF-8")))
                .isNotEqualTo(tokens(project, Map.of("PATH", "/bin", "LANG", "en_US.UTF-8")));
    }

    @Test
    void a_module_that_declares_term_gets_the_callers() throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group   = "com.example"
                name    = "m"
                version = "1.0.0"

                [env]
                optional = ["TERM"]
                """);
        JkBuild project = JkBuildParser.parse(tmp.resolve("jk.toml"));

        assertThat(jvmEnv(project, Map.of("PATH", "/bin", "TERM", "xterm-kitty")))
                .containsEntry("TERM", "xterm-kitty");
    }

    private List<String> tokens(JkBuild project, Map<String, String> shell) throws Exception {
        return WorkerEnv.withEngineEnvironment(
                Map.of("PATH", "/engine/bin", "TERM", "xterm-256color"),
                () -> RequestShell.where(
                        shell, () -> SuiteEnvKeys.tokens(project.build(), tmp, TestSelection.DEFAULT)));
    }

    private Map<String, String> jvmEnv(JkBuild project, Map<String, String> shell) throws Exception {
        return WorkerEnv.withEngineEnvironment(
                Map.of("PATH", "/engine/bin", "TERM", "xterm-256color"),
                () -> RequestShell.where(
                        shell, () -> TestEnv.forModuleUnseeded(project, tmp, BuildLayout.of(tmp, project))
                                .environment()));
    }

    private JkBuild project() throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group   = "com.example"
                name    = "m"
                version = "1.0.0"
                """);
        return JkBuildParser.parse(tmp.resolve("jk.toml"));
    }
}
