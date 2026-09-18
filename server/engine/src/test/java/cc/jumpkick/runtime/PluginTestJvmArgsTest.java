// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plugin step's {@code contributesTestJvmArgs} file reaches the forked test JVM line by line:
 * blank lines are nothing, a declared file the step did not write is nothing, and declaration
 * order is argument order.
 */
class PluginTestJvmArgsTest {

    private static final String TOML = """
            group = "g"
            name = "app"
            version = "1.0"
            java = 25
            """;

    private static TaskDecl decl(String name, List<String> jvmArgFiles) {
        return new TaskDecl(
                name,
                List.of(),
                List.of("classes"),
                List.of("out"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                jvmArgFiles,
                null,
                null,
                false);
    }

    @Test
    void every_non_blank_line_of_each_declared_file_is_one_argument(@TempDir Path tmp) throws Exception {
        JkBuild project = JkBuildParser.parse(TOML);
        BuildLayout layout = BuildLayout.of(tmp, project);
        TaskDecl model = decl("model", List.of("out/jvm.args"));
        TaskDecl silent = decl("silent", List.of("out/never-written.args"));
        Path file = PluginBuild.taskScratch(layout, "model").resolve("out/jvm.args");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "-Dframework.model=/m/target/model.json\n\n  -Xshare:off  \n");

        List<String> args =
                PlannerKsp.pluginTestJvmArgs(layout, new PluginDeclarations(List.of(model, silent), null, List.of()));

        assertThat(args).containsExactly("-Dframework.model=/m/target/model.json", "-Xshare:off");
    }

    @Test
    void a_step_that_feeds_the_test_jvm_only_through_arguments_is_test_only() {
        TaskDecl model = decl("model", List.of("out/jvm.args"));
        assertThat(model.feedsTests()).isTrue();
        assertThat(model.testOnly()).isTrue();
        assertThat(model.packageTime()).isFalse();
        assertThat(PlannerPlugin.pluginWindow(model)).isEqualTo(BuildStage.TEST);
        assertThat(PlannerPlugin.pluginRequires(model, null))
                .as("the compiled classes it reads are complete before it runs")
                .contains(TaskNames.COPY_RESOURCES);
    }

    @Test
    void no_declarations_means_no_arguments(@TempDir Path tmp) throws Exception {
        BuildLayout layout = BuildLayout.of(tmp, JkBuildParser.parse(TOML));
        assertThat(PlannerKsp.pluginTestJvmArgs(layout, null)).isEmpty();
    }
}
