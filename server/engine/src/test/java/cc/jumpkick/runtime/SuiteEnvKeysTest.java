// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.RequestShell;
import cc.jumpkick.config.ResolvedSecrets;
import cc.jumpkick.config.SecretRedactor;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.base.TestEnv;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the environment contributes to a suite's key: a module that inherits the shell is keyed on
 * all of it but the session churn of a terminal, and a declared credential is masked wherever its
 * value would be printed.
 */
class SuiteEnvKeysTest {

    @TempDir
    Path tmp;

    @Test
    void an_inheriting_suite_is_keyed_on_the_shell_less_terminal_and_session_churn() throws Exception {
        JkBuild project = project("[env]\ninherit = true\n");
        Map<String, String> shell = new HashMap<>(Map.of("PATH", "/bin", "APP_MODE", "a"));

        String first = inherited(project, shell, "ITERM_SESSION_ID", "w0t0p0", "COLUMNS", "80");
        String otherTab = inherited(project, shell, "ITERM_SESSION_ID", "w1t3p0", "COLUMNS", "132");
        shell.put("APP_MODE", "b");
        String otherValue = inherited(project, shell, "ITERM_SESSION_ID", "w0t0p0", "COLUMNS", "80");

        assertThat(otherTab).as("a new terminal tab replays the suite").isEqualTo(first);
        assertThat(otherValue).as("a changed variable reruns it").isNotEqualTo(first);
    }

    @Test
    void a_declared_name_that_reads_as_a_credential_is_masked_and_others_are_not() throws Exception {
        ResolvedSecrets.clear();
        JkBuild project = project("[env]\nrequire = [\"MY_API_TOKEN\"]\noptional = [\"FEATURE_FLAG\"]\n");
        RequestShell.where(
                Map.of("PATH", "/bin", "MY_API_TOKEN", "tok-1234567890", "FEATURE_FLAG", "enabled-everywhere"),
                () -> TestEnv.forModuleUnseeded(project, tmp, BuildLayout.of(tmp, project)));

        SecretRedactor redactor = ResolvedSecrets.plus(tmp, SecretRedactor.none());
        assertThat(redactor.redact("got tok-1234567890")).isEqualTo("got " + SecretRedactor.MASK);
        assertThat(redactor.redact("flag enabled-everywhere")).isEqualTo("flag enabled-everywhere");
        ResolvedSecrets.clear();
    }

    private String inherited(JkBuild project, Map<String, String> shell, String... churn) throws Exception {
        Map<String, String> vars = new HashMap<>(shell);
        for (int i = 0; i < churn.length; i += 2) vars.put(churn[i], churn[i + 1]);
        return RequestShell.where(vars, () -> SuiteEnvKeys.tokens(project.build(), tmp, TestSelection.DEFAULT).stream()
                .filter(t -> t.startsWith("inherited-env:"))
                .findFirst()
                .orElseThrow());
    }

    private JkBuild project(String extra) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                group   = "com.example"
                name    = "m"
                version = "1.0.0"
                """ + extra);
        return JkBuildParser.parse(tmp.resolve("jk.toml"));
    }
}
