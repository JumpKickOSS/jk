// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.host.Os;
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
 * A test JVM runs in a fixed locale, {@code en-US} unless the module's {@code [test] locale} names
 * another, so the caller's {@code LANG} and {@code LC_*} neither reach the suite nor key it and hosts
 * on {@code en_US}, {@code C.UTF-8} and {@code de_DE} share its result. Under {@code
 * [test] locale = "inherit"} the locale variables a suite is keyed on are the caller's, the shell
 * running {@code jk}, and so are the ones its test JVM gets: {@code LC_TIME=de_DE.UTF-8 jk test}
 * reaches the suite and its key. The terminal is not: a test JVM's {@code TERM} is a fixed {@code dumb} and no other terminal
 * variable reaches it, so a suite started from another terminal keys and runs the same, unless the
 * module declares {@code TERM}.
 */
class SuiteLocaleEnvTest {

    private static final String LC_TIME = "de_DE.probe-locale";

    @TempDir
    Path tmp;

    @Test
    void by_default_the_callers_locale_neither_keys_nor_reaches_the_suite() throws Exception {
        JkBuild project = project("");
        Map<String, String> german = Map.of("PATH", "/bin", "LANG", "de_DE.UTF-8", "LC_ALL", "de_DE.UTF-8");
        Map<String, String> c = Map.of("PATH", "/bin", "LANG", "C.UTF-8", "LC_TIME", LC_TIME);

        assertThat(tokens(project, german))
                .as("two builds differing only in the shell's locale take one stamp")
                .isEqualTo(tokens(project, c))
                .contains("test-locale:en-US")
                .noneMatch(t -> t.startsWith("machine-env:"));
        Map<String, String> env = jvmEnv(project, german);
        if (!Os.isWindows()) {
            String fixed = Os.isDarwin() ? "en_US.UTF-8" : "C.UTF-8";
            assertThat(env)
                    .containsEntry("LC_ALL", fixed)
                    .containsEntry("LANG", fixed)
                    .containsEntry("LANGUAGE", "");
        }
        assertThat(TestStampInputs.testJvmArgs(project, null))
                .as("the JVM's default locale, before any flag of the module's")
                .startsWith("-Duser.language=en", "-Duser.country=US");
    }

    @Test
    void a_declared_locale_keys_and_reaches_the_suite() throws Exception {
        JkBuild german = project("[test]\nlocale = \"de-DE\"\n");
        assertThat(tokens(german, Map.of("PATH", "/bin"))).contains("test-locale:de-DE");
        assertThat(TestStampInputs.testJvmArgs(german, null)).startsWith("-Duser.language=de", "-Duser.country=DE");
        assertThat(tokens(german, Map.of("PATH", "/bin"))).isNotEqualTo(tokens(project(""), Map.of("PATH", "/bin")));
    }

    @Test
    void a_locale_that_is_not_a_tag_is_refused() {
        assertThatThrownBy(() -> project("[test]\nlocale = \"de_DE.UTF-8\"\n"))
                .hasMessageContaining("[test].locale must be a BCP 47 tag");
    }

    @Test
    void an_inherited_lc_time_keys_the_suite_and_reaches_the_test_jvm() throws Exception {
        JkBuild project = project(INHERIT);
        assertThat(tokens(project, Map.of("PATH", "/bin", "LC_TIME", LC_TIME)))
                .contains("machine-env:LC_TIME=" + LC_TIME)
                .noneMatch(t -> t.startsWith("test-locale:"));
        assertThat(jvmEnv(project, Map.of("PATH", "/bin", "LC_TIME", LC_TIME))).containsEntry("LC_TIME", LC_TIME);
        assertThat(TestStampInputs.testJvmArgs(project, null))
                .noneMatch(a -> a.startsWith("-Duser.language") || a.startsWith("-Duser.country"));
    }

    @Test
    void a_different_callers_terminal_keys_and_runs_the_suite_the_same() throws Exception {
        JkBuild project = project("");
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
    void an_inherited_lang_keys_the_suite_apart() throws Exception {
        JkBuild project = project(INHERIT);

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

    private static final String INHERIT = "[test]\nlocale = \"inherit\"\n";

    private JkBuild project(String tables) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group   = "com.example"
                name    = "m"
                version = "1.0.0"
                """ + tables);
        return JkBuildParser.parse(tmp.resolve("jk.toml"));
    }
}
