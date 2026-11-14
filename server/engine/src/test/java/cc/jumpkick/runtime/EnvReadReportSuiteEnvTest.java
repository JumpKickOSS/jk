// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.model.JkBuild;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A variable the test JVM was given is never reported as one it saw no value for. */
class EnvReadReportSuiteEnvTest {

    private static final JkBuild PROJECT = JkBuildParser.parse("""
            group = "ex"
            name = "m"
            version = "1.0.0"

            [test.suites.integration]
            tags = ["integration"]
            env = { SUITE_MARK = "integration" }
            """);

    @Test
    void a_suite_env_variable_the_test_read_is_not_reported() {
        var looked = EnvReadReport.lookedUp(
                List.of(new EnvReadReport.Lookup("ex.CartTest", "ex.CartTest", "SUITE_MARK", true)),
                EnvReadReport.declared(PROJECT),
                WorkerEnv.strict(),
                name -> true,
                false);

        assertThat(looked)
                .as("SUITE_MARK comes from [test.suites.integration] env")
                .isEmpty();
    }

    @Test
    void java_home_which_every_test_jvm_gets_is_not_reported() {
        var looked = EnvReadReport.lookedUp(
                List.of(new EnvReadReport.Lookup("ex.JdkTest", "ex.JdkTest", "JAVA_HOME", true)),
                EnvReadReport.declared(PROJECT),
                WorkerEnv.strict(),
                name -> true,
                false);

        assertThat(looked).isEmpty();
        assertThat(EnvReadReport.reaches("JAVA_HOME", EnvReadReport.declared(PROJECT), WorkerEnv.strict()))
                .as("a class file reading JAVA_HOME is not told to declare it")
                .isTrue();
    }

    @Test
    void a_suite_env_name_is_declared_even_when_the_lookup_saw_no_value() {
        var looked = EnvReadReport.lookedUp(
                List.of(new EnvReadReport.Lookup("ex.PlainTest", "ex.PlainTest", "SUITE_MARK", false)),
                EnvReadReport.declared(PROJECT),
                WorkerEnv.strict(),
                name -> true,
                false);

        assertThat(looked)
                .as("the default suite's JVM does not get SUITE_MARK, but the manifest declares it for its suite")
                .isEmpty();
    }
}
