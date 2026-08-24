// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**SPI anchor steps must be dependency-gated in the build DAG. */
class BuildLogicAnchorGatingTest {

    @Test
    void before_package_requires_resources_and_is_required_by_package(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        BuildPlan p = plan(project, dir.resolve("cache"), false);
        Map<String, Task> byName = index(p);

        assertThat(byName)
                .containsKeys(
                        TaskNames.BUILD_LOGIC_BEFORE_COMPILE,
                        TaskNames.BUILD_LOGIC_AFTER_COMPILE,
                        TaskNames.BUILD_LOGIC_BEFORE_PACKAGE,
                        TaskNames.COPY_RESOURCES,
                        TaskNames.PACKAGE_JAR,
                        TaskNames.COMPILE_TEST,
                        TaskNames.RUN_TESTS,
                        TaskNames.COMPILE_JAVA);

        Task preCompile = byName.get(TaskNames.BUILD_LOGIC_BEFORE_COMPILE);
        Task after = byName.get(TaskNames.BUILD_LOGIC_AFTER_COMPILE);
        Task before = byName.get(TaskNames.BUILD_LOGIC_BEFORE_PACKAGE);
        Task resources = byName.get(TaskNames.COPY_RESOURCES);
        Task packageJar = byName.get(TaskNames.PACKAGE_JAR);
        Task compileTest = byName.get(TaskNames.COMPILE_TEST);
        Task compileJava = byName.get(TaskNames.COMPILE_JAVA);

        // BEFORE_COMPILE sits after setup and before language compile.
        assertThat(preCompile.requires()).contains(TaskNames.PARSE_BUILD, TaskNames.RESOLVE_DEPS, TaskNames.ENSURE_JDK);
        assertThat(compileJava.requires()).contains(TaskNames.BUILD_LOGIC_BEFORE_COMPILE);
        assertThat(preCompile.stage().wireName()).isEqualTo("generate");

        // AFTER_COMPILE is ordered before consumers that write/read main classes.
        assertThat(resources.requires()).contains(TaskNames.BUILD_LOGIC_AFTER_COMPILE);
        assertThat(compileTest.requires()).contains(TaskNames.BUILD_LOGIC_AFTER_COMPILE);
        assertThat(after.requires()).isNotEmpty(); // mainCompile at minimum

        // BEFORE_PACKAGE is not level-0: needs resources — and since JK-2211 never tests, so
        // artifact creation overlaps the suite. Tests stay scheduled via the terminal join.
        assertThat(before.requires())
                .contains(TaskNames.COPY_RESOURCES)
                .doesNotContain(TaskNames.RUN_TESTS)
                .doesNotContain(TaskNames.PARSE_BUILD);

        // package-jar waits on BEFORE_PACKAGE (not only resources).
        assertThat(packageJar.requires()).contains(TaskNames.BUILD_LOGIC_BEFORE_PACKAGE);
    }

    @Test
    void before_package_skips_run_tests_when_skip_tests(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        BuildPlan p = plan(project, dir.resolve("cache"), true);
        Map<String, Task> byName = index(p);

        assertThat(byName).containsKey(TaskNames.BUILD_LOGIC_BEFORE_PACKAGE);
        assertThat(byName).doesNotContainKey(TaskNames.RUN_TESTS);
        assertThat(byName.get(TaskNames.BUILD_LOGIC_BEFORE_PACKAGE).requires())
                .contains(TaskNames.COPY_RESOURCES)
                .doesNotContain(TaskNames.RUN_TESTS);
        assertThat(byName.get(TaskNames.PACKAGE_JAR).requires()).contains(TaskNames.BUILD_LOGIC_BEFORE_PACKAGE);
    }

    private static Map<String, Task> index(BuildPlan p) {
        return p.steps().stream().collect(Collectors.toMap(Task::name, Function.identity(), (a, b) -> a));
    }

    private static Path scaffold(Path dir) throws Exception {
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
        return project;
    }

    private static BuildPlan plan(Path project, Path cache, boolean skipTests) {
        FilesCreateCache(cache);
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
                false,
                false,
                Set.of(),
                SessionContext.current());
        // Core + tails, same as jk build: since JK-2211 run-tests is a terminal LEAF joined by
        // the tails (never a package prerequisite), so a core-only build would prune it.
        BuildPlan.Builder b = BuildPlanner.coreBuilder(in);
        BuildPlanner.appendDeclaredTails(b, in);
        return b.build();
    }

    private static void FilesCreateCache(Path cache) {
        try {
            Files.createDirectories(cache);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
