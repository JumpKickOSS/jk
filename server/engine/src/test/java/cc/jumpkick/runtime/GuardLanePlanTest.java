// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.guard.rules.GuardsPresence;
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
    void a_rules_file_adds_the_module_lane_after_the_compiles_and_the_model_lane_at_the_root(@TempDir Path dir)
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
        assertThat(task(byName, TaskNames.GUARD).requires())
                .containsExactly(TaskNames.COMPILE_JAVA, TaskNames.COMPILE_TEST); // the lane indexes test classes too
        assertThat(task(byName, TaskNames.GUARD_MODEL).requires()).containsExactly(TaskNames.RESOLVE_DEPS);
        assertThat(byName).as("the tree lane is a gate step").doesNotContainKey(TaskNames.GUARD_TREE);
    }

    @Test
    void on_build_false_moves_the_module_lane_to_the_gate(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir, true);
        Files.writeString(
                project.resolve("jk.toml"),
                Files.readString(project.resolve("jk.toml")) + "\n[guards]\non-build = false\n");
        Map<String, Task> byName = index(plan(project, dir.resolve("cache"), TestSelection.DEFAULT));
        assertThat(byName).as("the model lane still runs").containsKey(TaskNames.GUARD_MODEL);
        assertThat(byName).as("the module lane waits for the gate").doesNotContainKey(TaskNames.GUARD);
        TestSelection gate = TestSelection.of(List.of("test", "integration"), false, List.of(), List.of(), false, true);
        Map<String, Task> gated = index(plan(project, dir.resolve("cache"), gate));
        assertThat(gated).containsKeys(TaskNames.GUARD, TaskNames.GUARD_MODEL, TaskNames.GUARD_TREE);
    }

    @Test
    void a_workspace_root_plans_the_workspace_lane_after_the_model_lane(@TempDir Path dir) throws Exception {
        Path root = workspace(dir);
        Map<String, Task> byName = index(plan(root, dir.resolve("cache"), TestSelection.DEFAULT));
        assertThat(byName).containsKeys(TaskNames.GUARD_MODEL, TaskNames.GUARD_WORKSPACE);
        assertThat(task(byName, TaskNames.GUARD_WORKSPACE).requires()).contains(TaskNames.GUARD_MODEL);
        assertThat(byName).doesNotContainKey(TaskNames.GUARD_TREE);
        // A member's own plan has no workspace lane: a workspace rule needs the workspace.
        Map<String, Task> member = index(plan(root.resolve("core"), dir.resolve("cache"), TestSelection.DEFAULT));
        assertThat(member).containsKey(TaskNames.GUARD).doesNotContainKey(TaskNames.GUARD_WORKSPACE);
        // on-build = false moves it to the gate with the module lanes.
        Files.writeString(
                root.resolve("jk.toml"), Files.readString(root.resolve("jk.toml")) + "\n[guards]\non-build = false\n");
        Map<String, Task> off = index(plan(root, dir.resolve("cache"), TestSelection.DEFAULT));
        assertThat(off).containsKey(TaskNames.GUARD_MODEL).doesNotContainKey(TaskNames.GUARD_WORKSPACE);
    }

    /**
     * A module with a guard suite compiles it as {@code compile-guard}, and the module lane requires
     * that compile. {@code jk compile} plans neither: it runs no lane, so its plan carries no guard
     * step to name a compile it does not have.
     */
    @Test
    void the_compile_verb_plans_no_lane_for_a_module_with_a_guard_suite(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir, true);
        Files.createDirectories(project.resolve("src/guard/java/demo"));
        Files.writeString(project.resolve("src/guard/java/demo/Shape.java"), "package demo; class Shape {}\n");
        Map<String, Task> full = index(plan(project, dir.resolve("cache"), TestSelection.DEFAULT));
        assertThat(task(full, TaskNames.GUARD).requires())
                .as("a build's module lane follows the guard suite's compile")
                .contains(TaskNames.COMPILE_GUARD);

        BuildPlanner.Inputs in = inputs(project, dir.resolve("cache"), TestSelection.DEFAULT);
        Map<String, Task> compile = index(BuildPlanner.coreBuilder(new BuildPlanner.Inputs(
                        in.dir(),
                        in.cache(),
                        in.buildFile(),
                        in.lockFile(),
                        in.lockDir(),
                        1,
                        0,
                        null,
                        null,
                        true,
                        false,
                        false,
                        true,
                        Set.of(),
                        in.session()))
                .build());
        assertThat(compile).containsKey(TaskNames.COMPILE_JAVA);
        assertThat(compile.keySet()).noneMatch(n -> n.startsWith(TaskNames.GUARD));
        assertThat(compile).doesNotContainKey(TaskNames.COMPILE_GUARD);
        for (Task t : compile.values()) {
            assertThat(t.requires()).noneMatch(n -> n.startsWith(TaskNames.GUARD) || n.equals(TaskNames.COMPILE_GUARD));
        }
    }

    private static Path workspace(Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "t"
                name = "ws"
                version = "0.0.1"
                jdk = 25

                [workspace]
                modules = ["core", "app"]
                """);
        for (String m : List.of("core", "app")) {
            Files.createDirectories(root.resolve(m).resolve("src/main/java/demo"));
            Files.writeString(
                    root.resolve(m).resolve("jk.toml"),
                    "group = \"t\"\nname = \"" + m + "\"\nversion = \"0.0.1\"\njdk = 25\n");
            Files.writeString(
                    root.resolve(m).resolve("src/main/java/demo/" + m + ".java"),
                    "package demo; public class " + m + " {}\n");
        }
        Files.writeString(root.resolve(GuardsPresence.RULES_FILE), RULES);
        return root;
    }

    @Test
    void the_gate_adds_the_tree_lane(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir, true);
        TestSelection gate = TestSelection.of(List.of("test", "integration"), false, List.of(), List.of(), false, true);
        Map<String, Task> byName = index(plan(project, dir.resolve("cache"), gate));
        assertThat(byName).containsKey(TaskNames.GUARD_TREE);
        assertThat(task(byName, TaskNames.GUARD_TREE).requires()).contains(TaskNames.GUARD_MODEL, TaskNames.GUARD);
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

    private static final String OUTPUT_RULES = """
            [guards.jar-shape]
            kind = "output"
            jar  = { require-entries = ["META-INF/MANIFEST.MF"] }
            why  = "a jar is a contract"
            """;

    @Test
    void an_output_rule_plans_the_output_lane_after_packaging_and_nothing_else_does(@TempDir Path dir)
            throws Exception {
        Path project = scaffold(dir, true);
        Map<String, Task> without = index(plan(project, dir.resolve("cache"), TestSelection.DEFAULT));
        assertThat(without).as("no output rule, no output lane").doesNotContainKey(TaskNames.GUARD_OUTPUT);
        Files.writeString(project.resolve("jk-guards.toml"), RULES + OUTPUT_RULES);
        Map<String, Task> with = index(plan(project, dir.resolve("cache"), TestSelection.DEFAULT));
        assertThat(with).containsKey(TaskNames.GUARD_OUTPUT);
        assertThat(task(with, TaskNames.GUARD_OUTPUT).requires()).containsExactly(TaskNames.PACKAGE_JAR);
        // a test-only plan packages nothing: the lane follows the root lanes instead
        BuildPlanner.Inputs testOnly = inputs(project, dir.resolve("cache"), TestSelection.DEFAULT);
        BuildPlan.Builder b = BuildPlanner.coreBuilder(new BuildPlanner.Inputs(
                testOnly.dir(),
                testOnly.cache(),
                testOnly.buildFile(),
                testOnly.lockFile(),
                testOnly.lockDir(),
                1,
                0,
                null,
                null,
                false,
                false,
                true,
                false,
                Set.of(),
                testOnly.session()));
        Map<String, Task> gated = index(b.build());
        assertThat(gated).containsKey(TaskNames.GUARD_OUTPUT);
        assertThat(task(gated, TaskNames.GUARD_OUTPUT).requires()).doesNotContain(TaskNames.PACKAGE_JAR);
    }

    /** The plan's task named {@code name}; that it is in the plan is part of what the test asserts. */
    private static Task task(Map<String, Task> byName, String name) {
        return requireNonNull(byName.get(name), name);
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
