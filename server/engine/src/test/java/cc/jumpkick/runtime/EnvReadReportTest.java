// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.test.EnvReadsTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A suite's reads of variables its module never declares are reported with the declaration that
 * would give them; what the module declares, or every test JVM gets, is not.
 */
class EnvReadReportTest {

    @TempDir
    Path tmp;

    @Test
    void a_test_class_reading_an_undeclared_name_is_reported_with_the_declaration() throws Exception {
        JkBuild project = JkBuildParser.parse("""
                group = "ex"
                name = "m"
                version = "1.0.0"

                [env]
                optional = ["FEATURE_X"]
                """);
        Path testClasses = classes("test-classes", "ex/AccessSomeApiTest", "MY_TOKEN_VALUE", "FEATURE_X");
        Path mainClasses = classes("classes", "ex/Config", "APP_DATABASE_URL", "FEATURE_X");

        EnvReadReport.Findings passed = EnvReadReport.findings(
                project, WorkerEnv.strict(), testClasses, mainClasses, List.of(), tmp.resolve("cache"), false);
        EnvReadReport.Findings failed = EnvReadReport.findings(
                project, WorkerEnv.strict(), testClasses, mainClasses, List.of(), tmp.resolve("cache"), true);

        assertThat(passed.undeclared()).isEqualTo(Map.of("MY_TOKEN_VALUE", "ex.AccessSomeApiTest"));
        assertThat(failed.undeclared())
                .as("a failed suite names what its main classes read too")
                .containsEntry("APP_DATABASE_URL", "ex.Config");
        assertThat(failed.opaque()).contains("ex.Config", "ex.AccessSomeApiTest");
        assertThat(EnvReadReport.undeclared("ex.AccessSomeApiTest", "MY_TOKEN_VALUE", "core/jk.toml"))
                .isEqualTo("AccessSomeApiTest reads MY_TOKEN_VALUE, which core/jk.toml does not declare;"
                        + " add to [env] optional: \"MY_TOKEN_VALUE\"");
    }

    @Test
    void allow_listed_and_jk_set_names_reach_every_test_jvm_and_are_not_reported() throws Exception {
        JkBuild project = JkBuildParser.parse("group = \"ex\"\nname = \"m\"\nversion = \"1.0.0\"\n");
        Path testClasses = classes("test-classes", "ex/ReadsMachine", "PATH", "JK_HOME");

        assertThat(EnvReadReport.findings(
                                project,
                                WorkerEnv.strict().with(Map.of("JK_HOME", "/sandbox")),
                                testClasses,
                                tmp.resolve("none"),
                                List.of(),
                                tmp.resolve("cache"),
                                false)
                        .undeclared())
                .isEmpty();
    }

    @Test
    void what_a_test_jvm_looked_up_and_was_not_given_is_named_with_its_test_class() throws Exception {
        JkBuild project = JkBuildParser.parse(
                "group = \"ex\"\nname = \"m\"\nversion = \"1.0.0\"\n\n[env]\noptional = [\"FEATURE_X\"]\n");
        Path records = Files.createDirectories(tmp.resolve("records"));
        Files.writeString(records.resolve("101.lookups"), """
                ex.AccessSomeApiTest\tex.AccessSomeApiTest\tMY_TOKEN_VALUE\tunset
                ex.AccessSomeApiTest\tcom.api.Client\tAPI_REGION\tunset
                ex.AccessSomeApiTest\tex.AccessSomeApiTest\tFEATURE_X\tunset
                ex.AccessSomeApiTest\tex.AccessSomeApiTest\tPATH\tset
                \tcom.lib.Boot\tLIB_HOME\tunset
                ex.Other\tcom.lib.Scan\t*\tset
                ex.SlowTest\torg.junit.jupiter.api.condition.EnabledIfEnvironmentVariableCondition\tRUN_SLOW\tunset
                """);
        var lookups = EnvReadReport.lookups(records);
        var declared = EnvReadReport.declared(project);
        Predicate<String> testClass = "ex.AccessSomeApiTest"::equals;

        assertThat(EnvReadReport.lookedUp(lookups, declared, WorkerEnv.strict(), testClass, false))
                .as("a passing suite: what the test classes and JUnit's conditions looked up")
                .isEqualTo(Map.of("MY_TOKEN_VALUE", "ex.AccessSomeApiTest", "RUN_SLOW", "ex.SlowTest"));
        assertThat(EnvReadReport.probed(records)).isTrue();
        assertThat(EnvReadReport.probed(tmp.resolve("unprobed"))).isFalse();
        assertThat(EnvReadReport.lookedUp(lookups, declared, WorkerEnv.strict(), testClass, true))
                .as("a failing suite: what the code under test and the libraries looked up too")
                .isEqualTo(Map.of(
                        "MY_TOKEN_VALUE", "ex.AccessSomeApiTest",
                        "API_REGION", "ex.AccessSomeApiTest",
                        "LIB_HOME", "com.lib.Boot",
                        "RUN_SLOW", "ex.SlowTest"));
        assertThat(EnvReadReport.lookedUp("ex.AccessSomeApiTest", "MY_TOKEN_VALUE", "core/jk.toml"))
                .isEqualTo("AccessSomeApiTest read MY_TOKEN_VALUE, which core/jk.toml does not declare;"
                        + " it saw no value. Add to [env] require: \"MY_TOKEN_VALUE\"");
    }

    /** A classes tree holding one class that reads {@code name} by a constant and {@code condition} by a condition. */
    private Path classes(String dir, String internalName, String name, String condition) throws Exception {
        Path root = tmp.resolve(dir);
        Path file = root.resolve(internalName + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, EnvReadsTest.reader(internalName, name, condition));
        return root;
    }
}
