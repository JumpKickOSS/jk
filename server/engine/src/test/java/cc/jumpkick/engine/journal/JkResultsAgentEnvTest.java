// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The agent report names, on every verdict, each variable a suite read that its module does not
 * declare, with the {@code [env]} line that declares it.
 */
class JkResultsAgentEnvTest {

    @Test
    void an_ok_run_says_which_variable_never_reached_the_suite_and_the_line_that_declares_it() {
        BuildRecord.Diag foo = envUndeclared(
                "FOO",
                "EnvTest read FOO, which jk.toml does not declare; it saw no value. Add to [env] require: \"FOO\"");
        BuildRecord.Diag bar = envUndeclared(
                "BAR", "EnvTest reads BAR, which jk.toml does not declare. Add to [env] optional: \"BAR\"");

        assertThat(JkResultsAgent.render(record(true, new BuildRecord.Tests(1, 1, 0, 0), List.of(foo, bar))))
                .isEqualTo("""
                        OK test rest-service · 1 test · 500ms
                        W EnvTest read FOO, which jk.toml does not declare; it saw no value
                        FIX add to [env] require: "FOO"
                        W EnvTest reads BAR, which jk.toml does not declare
                        FIX add to [env] optional: "BAR"
                        """);
    }

    @Test
    void undeclared_variables_past_the_cap_are_one_count() {
        List<BuildRecord.Diag> diags = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String name = "V" + i;
            diags.add(envUndeclared(
                    name,
                    "T read " + name + ", which jk.toml does not declare; it saw no value. Add to [env] require: \""
                            + name + "\""));
        }
        diags.add(envUndeclared("", "3 more undeclared variables read; jk results lists the first 8"));

        String report = JkResultsAgent.render(record(true, new BuildRecord.Tests(1, 1, 0, 0), diags));
        assertThat(report.lines().filter(l -> l.startsWith("FIX ")).count()).isEqualTo(JkResultsAgent.MAX_PROBLEMS);
        assertThat(report).endsWith("W +6 more undeclared variables: jk results\n");
    }

    @Test
    void a_failed_run_names_an_undeclared_variable_and_a_computed_read_beside_its_failure() {
        BuildRecord.Diag computed = new BuildRecord.Diag(
                "warning",
                "/ws/rest-service",
                "run-tests",
                "env-computed",
                "the suite failed and Config read the environment by a name JumpKick cannot see",
                null,
                null);
        BuildRecord.Diag token = envUndeclared(
                "TOKEN",
                "ApiTest read TOKEN, which jk.toml does not declare; it saw no value. Add to [env] require: \"TOKEN\"");

        assertThat(JkResultsAgent.render(record(false, new BuildRecord.Tests(1, 0, 1, 0), List.of(token, computed))))
                .startsWith("FAIL test rest-service")
                .contains("W ApiTest read TOKEN, which jk.toml does not declare; it saw no value\n"
                        + "FIX add to [env] require: \"TOKEN\"\n"
                        + "W the suite failed and Config read the environment by a name JumpKick cannot see\n");
    }

    /**
     * A failed suite whose libraries scan the whole environment read hundreds of names: the failure
     * comes first, and the reads are one line naming the first readers and the count.
     */
    @Test
    void a_failed_run_puts_its_failure_first_and_many_reads_on_one_line() {
        List<BuildRecord.Diag> diags = new ArrayList<>();
        String[] readers = {"StoreTest", "DriverDataSource", "FileUtils", "Other"};
        for (int i = 0; i < 8; i++) {
            String name = "V" + i;
            diags.add(envUndeclared(
                    name,
                    readers[Math.min(i, 3)] + " read " + name
                            + ", which jk.toml does not declare; it saw no value. Add to [env] require: \"" + name
                            + "\""));
        }
        diags.add(envUndeclared("", "1292 more undeclared variables read; jk results lists the first 8"));

        String report = JkResultsAgent.render(record(false, new BuildRecord.Tests(1, 0, 1, 0), diags));

        assertThat(report)
                .startsWith("FAIL test rest-service")
                .endsWith("W 1300 undeclared variables read by StoreTest, DriverDataSource, FileUtils, …: jk results\n")
                .doesNotContain("FIX add to [env]");
    }

    private static BuildRecord.Diag envUndeclared(String name, String message) {
        return new BuildRecord.Diag(
                "warning",
                "/ws/rest-service",
                "run-tests",
                "env-undeclared",
                message,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "",
                0,
                0,
                0,
                List.of(),
                0,
                name);
    }

    private static BuildRecord record(boolean success, BuildRecord.Tests tests, List<BuildRecord.Diag> diags) {
        return new BuildRecord(
                "id",
                1,
                BuildRecord.SCHEMA,
                "test",
                "/ws/rest-service",
                "com.example:rest-service",
                "pid",
                1_000,
                1_500,
                500,
                success,
                false,
                success ? 0 : 1,
                "0.14.0",
                tests,
                List.of(),
                List.of(JkResultsAgentTest.RAN_TESTS),
                diags,
                "cli",
                null,
                null,
                null,
                false,
                null,
                7,
                null,
                List.of());
    }
}
