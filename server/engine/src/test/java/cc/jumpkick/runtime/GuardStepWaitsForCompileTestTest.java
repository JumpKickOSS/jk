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
 * compile-test. Without the edge a {@code --redo} rewrote {@code classes/test} under the indexer
 * and a class it had just listed was gone by the read (three consecutive full rebuilds, a different
 * module each time).
 */
class GuardStepWaitsForCompileTestTest {

    @Test
    void guard_step_requires_compile_test_when_the_plan_compiles_tests(@TempDir Path dir) throws Exception {
        scaffold(dir);
        Task guard = step(plan(dir, false), TaskNames.GUARD);
        assertThat(guard.requires()).contains(TaskNames.COMPILE_JAVA, TaskNames.COMPILE_TEST);
    }

    @Test
    void guard_step_does_not_name_a_compile_test_the_plan_lacks(@TempDir Path dir) throws Exception {
        scaffold(dir);
        BuildPlan compileOnly = plan(dir, true);
        assertThat(compileOnly.steps().stream().map(Task::name)).doesNotContain(TaskNames.COMPILE_TEST);
        assertThat(step(compileOnly, TaskNames.GUARD).requires()).doesNotContain(TaskNames.COMPILE_TEST);
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

    private static BuildPlan plan(Path dir, boolean compileOnly) {
        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                dir,
                dir.resolve("cache"),
                dir.resolve("jk.toml"),
                dir.resolve("jk-lock.toml"),
                dir,
                1,
                0,
                null,
                null,
                false,
                false,
                false,
                compileOnly,
                Set.of(),
                SessionContext.current());
        return BuildPlanner.fullPlan(in);
    }
}
