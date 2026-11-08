// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PluginConfig;
import cc.jumpkick.plugin.build.In;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two inputs the Helidon binding steps added. {@code In.testClasses()}: a TEST-window step reads
 * what compile-test wrote (the test binding scans it), so it waits for compile-test, its key moves
 * with the test classes, and a step that shapes anything but the test JVM may not declare it.
 * {@code In.recompile()}: a step told whether the build is the compile goal alone ({@code jk dev}'s
 * rebuild) is keyed by it, and only such a step.
 */
class PluginTestClassesInputTest {

    private static final String TOML = """
            group = "g"
            name = "app"
            version = "1.0"
            java = 25
            """;

    private static TaskDecl decl(List<String> inputs, List<String> testClasspath, List<String> classes) {
        return new TaskDecl(
                "test-binding",
                List.of(),
                inputs,
                List.of("out"),
                classes,
                List.of(),
                List.of(),
                List.of(),
                testClasspath,
                List.of(),
                null,
                null,
                false,
                null);
    }

    @Test
    void a_test_window_step_that_reads_the_test_classes_waits_for_compile_test() {
        TaskDecl step = decl(List.of(In.testClasses().wireName(), In.classes().wireName()), List.of("out"), List.of());
        assertThat(step.testOnly()).isTrue();
        assertThat(PlannerPlugin.pluginWindow(step)).isEqualTo(BuildStage.TEST);
        assertThat(PlannerPlugin.pluginRequires(step, null))
                .contains(TaskNames.COMPILE_TEST, TaskNames.BUILD_LOGIC_AFTER_COMPILE);
    }

    @Test
    void a_step_that_shapes_the_main_output_may_not_read_the_test_classes() {
        TaskDecl step = decl(List.of(In.testClasses().wireName()), List.of("out"), List.of("out"));
        assertThatThrownBy(() -> PlannerPlugin.pluginRequires(step, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("In.testClasses()");
    }

    @Test
    void the_key_moves_with_the_test_classes(@TempDir Path tmp) throws Exception {
        JkBuild project = JkBuildParser.parse(TOML);
        BuildLayout layout = BuildLayout.of(tmp, project);
        Path testClasses = Files.createDirectories(layout.testClassesDir());
        Files.writeString(testClasses.resolve("AppTest.class"), "one");
        List<String> input = List.of(In.testClasses().wireName());

        List<String> before = PlannerPlugin.declaredInputTokens(input, sources(tmp, layout));
        Files.writeString(testClasses.resolve("AppTest.class"), "two");
        List<String> after = PlannerPlugin.declaredInputTokens(input, sources(tmp, layout));

        assertThat(before).singleElement().asString().startsWith("test-classes:");
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void a_recompile_is_keyed_apart_only_for_a_step_that_declares_it(@TempDir Path tmp) throws Exception {
        BuildLayout layout = BuildLayout.of(tmp, JkBuildParser.parse(TOML));
        List<String> declared = List.of(In.classes().wireName(), In.recompile().wireName());
        List<String> undeclared = List.of(In.classes().wireName());

        assertThat(PlannerPlugin.declaredInputTokens(declared, sources(tmp, layout, true)))
                .isNotEqualTo(PlannerPlugin.declaredInputTokens(declared, sources(tmp, layout, false)))
                .contains("recompile:true");
        assertThat(PlannerPlugin.declaredInputTokens(undeclared, sources(tmp, layout, true)))
                .as("a step that does not ask is keyed the same under either goal")
                .isEqualTo(PlannerPlugin.declaredInputTokens(undeclared, sources(tmp, layout, false)));
    }

    private static PlannerPlugin.InputSources sources(Path tmp, BuildLayout layout) throws Exception {
        return sources(tmp, layout, false);
    }

    private static PlannerPlugin.InputSources sources(Path tmp, BuildLayout layout, boolean recompile)
            throws Exception {
        return new PlannerPlugin.InputSources(
                Files.createDirectories(tmp.resolve("classes")),
                List.of(),
                List.of(),
                List.of(),
                new PluginConfig("helidon", Map.of()),
                layout,
                tmp,
                Map.of(),
                PluginRepositories.NONE,
                recompile);
    }
}
