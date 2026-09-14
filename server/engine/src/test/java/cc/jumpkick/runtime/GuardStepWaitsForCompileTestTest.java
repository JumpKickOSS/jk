// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The module guard lane indexes the test classes as well as the main ones, so its step must follow
 * compile-test. Without the edge a rebuild rewrites {@code classes/test} under the indexer and a
 * class it has just listed is gone by the read.
 */
class GuardStepWaitsForCompileTestTest {

    @Test
    void guard_step_requires_compile_test_when_the_plan_compiles_tests(@TempDir Path dir) throws Exception {
        scaffold(dir);
        Task guard = step(plan(dir, false, false), TaskNames.GUARD);
        assertThat(guard.requires()).contains(TaskNames.COMPILE_JAVA, TaskNames.COMPILE_TEST);
    }

    @Test
    void guard_step_does_not_name_a_compile_test_the_plan_lacks(@TempDir Path dir) throws Exception {
        scaffold(dir);
        BuildPlan skipTests = plan(dir, true, false);
        assertThat(skipTests.steps().stream().map(Task::name)).doesNotContain(TaskNames.COMPILE_TEST);
        assertThat(step(skipTests, TaskNames.GUARD).requires()).doesNotContain(TaskNames.COMPILE_TEST);
    }

    /** {@code jk compile} runs no lane, so its plan has no guard step to hang an edge on. */
    @Test
    void a_compile_only_plan_has_no_guard_step(@TempDir Path dir) throws Exception {
        scaffold(dir);
        BuildPlan compileOnly = plan(dir, false, true);
        assertThat(compileOnly.steps().stream().map(Task::name))
                .contains(TaskNames.COMPILE_JAVA)
                .doesNotContain(TaskNames.GUARD, TaskNames.GUARD_MODEL, TaskNames.COMPILE_TEST);
    }

    /**
     * The lane indexes {@code classes/test} exactly when this plan compiles it. Under --skip-tests
     * or --compile-only no compile-test runs, and the directory holds whatever a previous build
     * left — bytecode the current sources no longer describe.
     */
    @Test
    void the_module_lane_indexes_test_classes_only_when_the_plan_compiles_them(@TempDir Path dir) {
        assertThat(PlannerGuards.indexesTestClasses(inputs(dir, false, false, false)))
                .isTrue();
        assertThat(PlannerGuards.indexesTestClasses(inputs(dir, true, false, false)))
                .as("--skip-tests: classes/test is a previous build's")
                .isFalse();
        assertThat(PlannerGuards.indexesTestClasses(inputs(dir, false, false, true)))
                .as("--compile-only")
                .isFalse();
        assertThat(PlannerGuards.indexesTestClasses(inputs(dir, true, true, false)))
                .as("a test-only run compiles its tests whatever --skip-tests says")
                .isTrue();
    }

    private static BuildPlanner.Inputs inputs(Path dir, boolean skipTests, boolean testOnly, boolean compileOnly) {
        return new BuildPlanner.Inputs(
                dir,
                dir.resolve("cache"),
                dir.resolve("jk.toml"),
                dir.resolve("jk-lock.toml"),
                dir,
                1,
                0,
                null,
                null,
                skipTests,
                false,
                testOnly,
                compileOnly,
                Set.of(),
                SessionContext.current());
    }

    private static void scaffold(Path dir) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"), "group=\"com.example\"\nname=\"g\"\nversion=\"0.1.0\"\njdk=25\njava=25\n");
        Files.writeString(dir.resolve(GuardsPresence.RULES_FILE), "");
        Files.createDirectories(dir.resolve("src/main/java/com/example"));
        Files.writeString(
                dir.resolve("src/main/java/com/example/Hello.java"), "package com.example;\nclass Hello {}\n");
        Files.createDirectories(dir.resolve("src/test/java/com/example"));
        Files.writeString(
                dir.resolve("src/test/java/com/example/HelloTest.java"), "package com.example;\nclass HelloTest {}\n");
    }

    private static Task step(BuildPlan plan, String name) {
        return plan.steps().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static BuildPlan plan(Path dir, boolean skipTests, boolean compileOnly) {
        return BuildPlanner.fullPlan(inputs(dir, skipTests, false, compileOnly));
    }
}
