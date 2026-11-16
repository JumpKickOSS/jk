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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The test compiles key the main classes tree with its {@code META-INF} resources, and {@code
 * [build-info]} writes {@code META-INF/build-info.properties} into that tree after the compile. With
 * no edge between them a compile-test the scheduler ran first keyed the tree without the file and
 * one it ran after keyed it with, so one tree took two compile keys and the suite, whose stamp names
 * the compile key, ran again under the verb that drew the other order (JK-NVBS1: hotel-reservations'
 * rest, {@code jk build -g} after an up-to-date {@code jk test -g}). Every step that compiles against
 * the tree now waits for the steps that write it last.
 */
class TestCompilesWaitForLateClassesWritersTest {

    @Test
    void the_test_compiles_wait_for_build_info(@TempDir Path dir) throws Exception {
        scaffold(dir, "\n[build-info]\n\n[test]\nfixtures = \"src/testFixtures/java\"\n");
        BuildPlan plan = BuildPlanner.fullPlan(inputs(dir, TestSelection.DEFAULT));
        assertThat(step(plan, TaskNames.COMPILE_TEST).requires()).contains(TaskNames.BUILD_INFO);
        assertThat(step(plan, TaskNames.COMPILE_TEST_FIXTURES).requires()).contains(TaskNames.BUILD_INFO);
    }

    /** The gate's selection, which compiles the guard suite as well. */
    @Test
    void under_the_guard_selection_too(@TempDir Path dir) throws Exception {
        scaffold(dir, "\n[build-info]\n");
        Files.createDirectories(dir.resolve("src/guard/java/com/example"));
        Files.writeString(
                dir.resolve("src/guard/java/com/example/HouseRules.java"),
                "package com.example;\nclass HouseRules {}\n");
        TestSelection guard = TestSelection.of(List.of(), false, List.of(), List.of(), true, false, false);
        BuildPlan plan = BuildPlanner.fullPlan(inputs(dir, guard));
        assertThat(step(plan, TaskNames.COMPILE_TEST).requires()).contains(TaskNames.BUILD_INFO);
        assertThat(step(plan, TaskNames.COMPILE_GUARD).requires()).contains(TaskNames.BUILD_INFO);
    }

    @Test
    void without_a_late_writer_nothing_is_added(@TempDir Path dir) throws Exception {
        scaffold(dir, "");
        BuildPlan plan = BuildPlanner.fullPlan(inputs(dir, TestSelection.DEFAULT));
        assertThat(plan.steps().stream().map(Task::name)).doesNotContain(TaskNames.BUILD_INFO);
        assertThat(step(plan, TaskNames.COMPILE_TEST).requires())
                .doesNotContain(TaskNames.BUILD_INFO, TaskNames.MODULE_SERVICES);
    }

    private static BuildPlanner.Inputs inputs(Path dir, TestSelection selection) {
        return TestInputs.of(dir, dir.resolve("cache"), SessionContext.current().withTestSelection(selection));
    }

    private static void scaffold(Path dir, String tables) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"),
                "group=\"com.example\"\nname=\"g\"\nversion=\"0.1.0\"\njdk=25\njava=25\n" + tables);
        Files.createDirectories(dir.resolve("src/main/java/com/example"));
        Files.writeString(
                dir.resolve("src/main/java/com/example/Hello.java"), "package com.example;\nclass Hello {}\n");
        Files.createDirectories(dir.resolve("src/test/java/com/example"));
        Files.writeString(
                dir.resolve("src/test/java/com/example/HelloTest.java"), "package com.example;\nclass HelloTest {}\n");
        Files.createDirectories(dir.resolve("src/testFixtures/java/com/example"));
        Files.writeString(
                dir.resolve("src/testFixtures/java/com/example/Fixture.java"),
                "package com.example;\nclass Fixture {}\n");
    }

    private static Task step(BuildPlan plan, String name) {
        return plan.steps().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + name + " in "
                        + plan.steps().stream().map(Task::name).toList()));
    }
}
