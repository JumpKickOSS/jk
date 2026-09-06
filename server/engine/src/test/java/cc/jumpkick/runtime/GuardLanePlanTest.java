// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where the guard lanes land in a plan — and, first, that a project without guards gets a plan with
 * no trace of them: no task, no requirement edge, nothing for the forecast to price.
 */
class GuardLanePlanTest {

    private static final String RULES = """
            [guards.one-owner]
            kind = "split-package"
            why  = "one module per package"
            """;

    @Test
    void a_project_without_guards_plans_no_guard_task(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir, false);
        Map<String, Task> byName = index(plan(project, dir.resolve("cache"), TestSelection.DEFAULT));
        assertThat(byName.keySet()).noneMatch(n -> n.startsWith(TaskNames.GUARD));
        for (Task t : byName.values()) {
            assertThat(t.requires()).noneMatch(n -> n.startsWith(TaskNames.GUARD));
        }
    }

    @Test
    void a_rules_file_adds_the_module_lane_after_compile_and_the_model_lane_at_the_root(@TempDir Path dir)
            throws Exception {
        Path project = scaffold(dir, true);
        assertThat(PlannerGuards.enabledAt(project)).as("enabledAt").isTrue();
        BuildPlanner.Inputs in = inputs(project, dir.resolve("cache"), TestSelection.DEFAULT);
        assertThat(PlannerGuards.detect(in).enabled())
                .as("detect: lockDir=" + in.lockDir())
                .isTrue();
        assertThat(PlannerResources.invocationRoot(project))
                .as("invocation root")
                .isTrue();
        Map<String, Task> byName = index(plan(project, dir.resolve("cache"), TestSelection.DEFAULT));
        assertThat(byName).containsKeys(TaskNames.GUARD, TaskNames.GUARD_MODEL);
        assertThat(byName.get(TaskNames.GUARD).requires()).containsExactly(TaskNames.COMPILE_JAVA);
        assertThat(byName.get(TaskNames.GUARD_MODEL).requires()).containsExactly(TaskNames.RESOLVE_DEPS);
        assertThat(byName).as("the tree lane is a gate step").doesNotContainKey(TaskNames.GUARD_TREE);
    }

    @Test
    void the_gate_adds_the_tree_lane(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir, true);
        TestSelection gate = TestSelection.of(List.of("test", "integration"), false, List.of(), List.of(), false, true);
        Map<String, Task> byName = index(plan(project, dir.resolve("cache"), gate));
        assertThat(byName).containsKey(TaskNames.GUARD_TREE);
        assertThat(byName.get(TaskNames.GUARD_TREE).requires()).contains(TaskNames.GUARD_MODEL, TaskNames.GUARD);
    }

    @Test
    void a_guards_table_alone_enables_the_lanes(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir, false);
        Files.writeString(
                project.resolve("jk.toml"),
                Files.readString(project.resolve("jk.toml")) + "\n[guards]\non-build = true\n");
        Map<String, Task> byName = index(plan(project, dir.resolve("cache"), TestSelection.DEFAULT));
        assertThat(byName).containsKeys(TaskNames.GUARD, TaskNames.GUARD_MODEL);
    }

    private static Map<String, Task> index(BuildPlan plan) {
        Map<String, Task> byName = new LinkedHashMap<>();
        for (Task t : plan.steps()) byName.put(t.name(), t);
        return byName;
    }

    private static Path scaffold(Path dir, boolean withRules) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("jk.toml"), """
                group = "t"
                name = "t"
                version = "0.0.1"
                jdk = 25
                """);
        Files.writeString(project.resolve("src/main/java/demo/App.java"), "package demo; public class App {}\n");
        if (withRules) Files.writeString(project.resolve("jk-guards.toml"), RULES);
        return project;
    }

    private static BuildPlan plan(Path project, Path cache, TestSelection sel) throws Exception {
        BuildPlanner.Inputs in = inputs(project, cache, sel);
        BuildPlan.Builder b = BuildPlanner.coreBuilder(in);
        PlannerTails.appendDeclaredTails(b, in);
        return b.build();
    }

    private static BuildPlanner.Inputs inputs(Path project, Path cache, TestSelection sel) throws Exception {
        Files.createDirectories(cache);
        return new BuildPlanner.Inputs(
                project,
                cache,
                project.resolve("jk.toml"),
                project.resolve("jk-lock.toml"),
                project,
                1,
                0,
                null,
                null,
                false,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current().withTestSelection(sel));
    }
}
