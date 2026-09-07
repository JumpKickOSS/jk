// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.BuildLogicToml;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Plan shape for the {@code gate} stem and {@code --scripts-only} / {@code --no-scripts}. */
class BuildLogicGuardPlanTest {

    @Test
    void default_test_plan_does_not_include_gate(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir, true);
        Map<String, Task> byName = index(plan(project, dir.resolve("cache"), false, true, TestSelection.DEFAULT));
        assertThat(byName).containsKey(TaskNames.RUN_TESTS);
        assertThat(byName).doesNotContainKey(TaskNames.BUILD_LOGIC_GUARD);
    }

    @Test
    void default_build_plan_does_not_include_gate(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir, true);
        Map<String, Task> byName = index(plan(project, dir.resolve("cache"), false, false, TestSelection.DEFAULT));
        assertThat(byName).containsKey(TaskNames.PACKAGE_JAR);
        assertThat(byName).doesNotContainKey(TaskNames.BUILD_LOGIC_GUARD);
    }

    @Test
    void gate_selection_adds_the_gate_step_after_tests(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir, true);
        TestSelection gate = TestSelection.of(List.of("test", "integration"), false, List.of(), List.of(), false, true);
        Map<String, Task> byName = index(plan(project, dir.resolve("cache"), false, true, gate));
        assertThat(byName).containsKey(TaskNames.BUILD_LOGIC_GUARD);
        assertThat(byName).containsKey(TaskNames.RUN_TESTS);
        assertThat(byName.get(TaskNames.BUILD_LOGIC_GUARD).requires()).contains(TaskNames.RUN_TESTS);
    }

    @Test
    void scripts_only_skips_junit_and_runs_gate(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir, true);
        TestSelection sel = TestSelection.of(List.of(), false, List.of(), List.of(), false, false, true, false);
        Map<String, Task> byName = index(plan(project, dir.resolve("cache"), false, true, sel));
        assertThat(byName).containsKey(TaskNames.BUILD_LOGIC_GUARD);
        assertThat(byName).doesNotContainKey(TaskNames.RUN_TESTS);
        assertThat(byName.get(TaskNames.BUILD_LOGIC_GUARD).requires()).contains(TaskNames.COPY_RESOURCES);
    }

    @Test
    void no_scripts_keeps_tests_without_gate(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir, true);
        TestSelection sel =
                TestSelection.of(List.of("test", "integration"), false, List.of(), List.of(), false, true, false, true);
        Map<String, Task> byName = index(plan(project, dir.resolve("cache"), false, true, sel));
        assertThat(byName).containsKey(TaskNames.RUN_TESTS);
        assertThat(byName).doesNotContainKey(TaskNames.BUILD_LOGIC_GUARD);
    }

    @Test
    void build_gate_skip_tests_requires_package(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir, true);
        TestSelection gate = TestSelection.of(List.of("test", "integration"), false, List.of(), List.of(), false, true);
        Map<String, Task> byName = index(plan(project, dir.resolve("cache"), true, false, gate));
        assertThat(byName).containsKey(TaskNames.BUILD_LOGIC_GUARD);
        assertThat(byName).doesNotContainKey(TaskNames.RUN_TESTS);
        assertThat(byName.get(TaskNames.BUILD_LOGIC_GUARD).requires()).contains(TaskNames.PACKAGE_JAR);
    }

    @Test
    void scripts_only_without_a_stem_is_a_config_error(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir, false);
        TestSelection sel = TestSelection.of(List.of(), false, List.of(), List.of(), false, false, true, false);
        assertThatThrownBy(() -> plan(project, dir.resolve("cache"), false, true, sel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(BuildLogicToml.NO_GUARD_SCRIPTS);
    }

    private static Map<String, Task> index(BuildPlan p) {
        return p.steps().stream().collect(Collectors.toMap(Task::name, Function.identity(), (a, b) -> a));
    }

    private static Path scaffold(Path dir, boolean withGate) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("jk.toml"), """
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        Files.writeString(project.resolve("src/main/java/demo/App.java"), "package demo; public class App {}\n");
        Files.createDirectories(project.resolve("src/test/java/demo"));
        Files.writeString(project.resolve("src/test/java/demo/AppTest.java"), "package demo; class AppTest {}\n");
        if (withGate) {
            Files.createDirectories(project.resolve(".jk"));
            Files.writeString(project.resolve(".jk/guard.groovy"), "// gate\n");
        }
        return project;
    }

    private static BuildPlan plan(Path project, Path cache, boolean skipTests, boolean testOnly, TestSelection sel) {
        try {
            Files.createDirectories(cache);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                project,
                cache,
                project.resolve("jk.toml"),
                project.resolve("jk-lock.toml"),
                project,
                1,
                0,
                null,
                null,
                skipTests,
                false,
                testOnly,
                false,
                Set.of(),
                SessionContext.current().withTestSelection(sel));
        BuildPlan.Builder b = BuildPlanner.coreBuilder(in);
        if (!testOnly) PlannerTails.appendDeclaredTails(b, in);
        return b.build();
    }
}
