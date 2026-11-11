// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ClasspathFingerprint;
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
                false,
                null);
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
                .contains(TaskNames.BUILD_LOGIC_AFTER_COMPILE);
    }

    @Test
    void no_declarations_means_no_arguments(@TempDir Path tmp) throws Exception {
        BuildLayout layout = BuildLayout.of(tmp, JkBuildParser.parse(TOML));
        assertThat(PlannerKsp.pluginTestJvmArgs(layout, null)).isEmpty();
    }

    /**
     * The forecast keys run-tests with the flags the run stamps: a plugin's contributed test JVM
     * flags first, then the module's. Left out, every module with such a plugin forecast a suite
     * re-run on every build that the build then replayed as green.
     */
    @Test
    void the_forecast_stamps_the_plugin_flags_the_run_stamps(@TempDir Path tmp) throws Exception {
        JkBuild project = JkBuildParser.parse(TOML);
        BuildLayout layout = BuildLayout.of(tmp, project);
        TaskDecl model = decl("model", List.of("out/jvm.args"));
        Path file = PluginBuild.taskScratch(layout, "model").resolve("out/jvm.args");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "-Dframework.model=/m/target/model.json\n");
        PluginDeclarations decls = new PluginDeclarations(List.of(model), null, List.of());

        List<String> runFlags = TestLaunch.testJvmArgs(layout, project, null, decls);
        assertThat(runFlags).startsWith("-Dframework.model=/m/target/model.json");

        List<String> forecastExtras =
                PlannerSupport.testStampExtras(tmp, project, null, ClasspathFingerprint.ON_DISK, runFlags);
        List<String> withoutPlugin = PlannerSupport.testStampExtras(tmp, project, null, ClasspathFingerprint.ON_DISK);
        assertThat(String.join("\n", forecastExtras)).contains("-Dframework.model=/m/target/model.json");
        assertThat(forecastExtras).isNotEqualTo(withoutPlugin);
    }
}
