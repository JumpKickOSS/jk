// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.TestFailureMode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code [test] failures}: a failing test fails the run unless the module says {@code report}. */
class ManifestTestFailuresTest {

    @TempDir
    Path tmp;

    private TestFailureMode parse(String toml) throws Exception {
        Path f = tmp.resolve("jk.toml");
        Files.writeString(f, "name = \"m\"\ngroup = \"g\"\nversion = \"1.0\"\n" + toml);
        return JkBuildParser.parse(f).build().testFailures();
    }

    @Test
    void a_failing_test_fails_the_run_by_default() throws Exception {
        assertThat(parse("")).isEqualTo(TestFailureMode.FAIL);
        assertThat(parse("[test]\nworkers = 2\n")).isEqualTo(TestFailureMode.FAIL);
    }

    @Test
    void report_and_fail_are_the_two_modes() throws Exception {
        assertThat(parse("[test]\nfailures = \"report\"\n")).isEqualTo(TestFailureMode.REPORT);
        assertThat(parse("[test]\nfailures = \"FAIL\"\n")).isEqualTo(TestFailureMode.FAIL);
    }

    @Test
    void anything_else_is_refused() {
        assertThatThrownBy(() -> parse("[test]\nfailures = \"ignore\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[test].failures must be \"fail\" or \"report\"");
        assertThatThrownBy(() -> parse("[test]\nfailures = true\n")).isInstanceOf(JkBuildParseException.class);
    }
}
