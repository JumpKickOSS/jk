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

    /** A classes tree holding one class that reads {@code name} by a constant and {@code condition} by a condition. */
    private Path classes(String dir, String internalName, String name, String condition) throws Exception {
        Path root = tmp.resolve(dir);
        Path file = root.resolve(internalName + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, EnvReadsTest.reader(internalName, name, condition));
        return root;
    }
}
