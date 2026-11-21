// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.runtime.NewProjectOps;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.testing.RepoRoot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The test-compile goal (what a graph question runs to compile a module's tests, {@code jk build
 * --target test-compile}) compiles the tests with the steps the build compiles them with: the
 * compile-test step and every step it waits for, with the same edges. The goal's plan once lacked
 * the {@code build-info} step that compile-test waits for, so on any project with
 * {@code [build-info]} (the library template writes it) it failed to plan ("step 'compile-test'
 * requires unknown 'build-info'") and every graph answer left src/test out.
 *
 * <p>Checked over every core template's own {@code jk.toml} as {@code jk new} writes it with its
 * default answers, and over the tables no template writes that add a step the test compile waits
 * for. ktor-3 is left out: it names its libraries by short names (ktor-server-netty) that the
 * library catalogue resolves, which a plan in this test does not reach, and it adds no table.
 */
class TestCompilePlanTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "java/library",
                "java/app",
                "java/cli",
                "java/cli-native",
                "java/plugin",
                "kotlin/library",
                "kotlin/app",
                "kotlin/cli",
                "scala/library",
                "scala/app",
                "groovy/app"
            })
    void a_templates_test_compile_is_the_builds(String template, @TempDir Path tmp) throws Exception {
        Path dest = NewProjectOps.create(new NewProjectOps.Request(
                        "demo",
                        tmp.toString(),
                        "com.example",
                        null,
                        null,
                        RepoRoot.find(TestCompilePlanTest.class)
                                .resolve("templates/" + template + ".g8")
                                .toString(),
                        true,
                        Map.of(),
                        false,
                        null,
                        tmp.toString()))
                .path();
        assertSameTestCompile(dest);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "[build-info]\n",
                "[build-info]\n[test]\nfixtures = \"src/testFixtures/java\"\n",
                "[build]\nmodule-services = true\n[build-info]\n",
                "[nullness]\n[build-info]\n[build]\nmodule-services = true\n"
                        + "[test]\nfixtures = \"src/testFixtures/java\"\n",
                ""
            })
    void with_each_table_that_adds_a_step_it_waits_for(String tables, @TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("jk.toml"), "group=\"com.example\"\nname=\"g\"\nversion=\"0.1.0\"\njava=25\n" + tables);
        write(dir, "src/main/java/com/example/Hello.java", "package com.example;\nclass Hello {}\n");
        write(dir, "src/test/java/com/example/HelloTest.java", "package com.example;\nclass HelloTest {}\n");
        write(dir, "src/testFixtures/java/com/example/Fixture.java", "package com.example;\nclass Fixture {}\n");
        assertSameTestCompile(dir);
    }

    /**
     * The test-compile plan plans, holds compile-test with the full build's closure under it, and
     * {@code jk compile}'s plan plans too.
     */
    private static void assertSameTestCompile(Path dir) {
        BuildPlanner.Inputs in = TestInputs.of(
                dir, dir.resolve("cache"), SessionContext.current().withTestSelection(TestSelection.DEFAULT));
        BuildPlan build = BuildPlanner.fullPlan(in);
        BuildPlan testCompile = BuildPlanner.fullPlan(in.withCompileOnly(true).withCompileTests(true));
        assertThat(closure(testCompile, TaskNames.COMPILE_TEST))
                .as("compile-test and what it waits for, test-compile goal vs build")
                .isEqualTo(closure(build, TaskNames.COMPILE_TEST));
        assertThat(BuildPlanner.fullPlan(in.withCompileOnly(true)).steps())
                .extracting(Task::name)
                .contains(PlannerResources.CLASSES_WHOLE);
    }

    /** {@code root} and every step it waits for, transitively, each with what it requires. */
    private static Map<String, List<String>> closure(BuildPlan plan, String root) {
        Map<String, Task> byName = new TreeMap<>();
        for (Task t : plan.steps()) byName.put(t.name(), t);
        assertThat(byName).as("steps of the plan").containsKey(root);
        Map<String, List<String>> out = new TreeMap<>();
        Deque<String> todo = new ArrayDeque<>(List.of(root));
        while (!todo.isEmpty()) {
            String name = todo.pop();
            if (out.containsKey(name)) continue;
            Task t = Objects.requireNonNull(byName.get(name), name);
            List<String> requires = t.requires().stream().sorted().toList();
            out.put(name, requires);
            todo.addAll(requires);
        }
        return out;
    }

    private static void write(Path dir, String rel, String text) throws Exception {
        Path f = dir.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }
}
