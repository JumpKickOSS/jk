// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.Step;
import cc.jumpkick.run.StepNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** JK-1061: SPI anchor steps must be dependency-gated in the build DAG. */
class BuildLogicAnchorGatingTest {

    @Test
    void before_package_requires_resources_and_is_required_by_package(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Pipeline p = pipeline(project, dir.resolve("cache"), false);
        Map<String, Step> byName = index(p);

        assertThat(byName)
                .containsKeys(
                        StepNames.BUILD_LOGIC_AFTER_COMPILE,
                        StepNames.BUILD_LOGIC_BEFORE_PACKAGE,
                        StepNames.COPY_RESOURCES,
                        StepNames.PACKAGE_JAR,
                        StepNames.COMPILE_TEST,
                        StepNames.RUN_TESTS);

        Step after = byName.get(StepNames.BUILD_LOGIC_AFTER_COMPILE);
        Step before = byName.get(StepNames.BUILD_LOGIC_BEFORE_PACKAGE);
        Step resources = byName.get(StepNames.COPY_RESOURCES);
        Step packageJar = byName.get(StepNames.PACKAGE_JAR);
        Step compileTest = byName.get(StepNames.COMPILE_TEST);

        // AFTER_COMPILE is ordered before consumers that write/read main classes.
        assertThat(resources.requires()).contains(StepNames.BUILD_LOGIC_AFTER_COMPILE);
        assertThat(compileTest.requires()).contains(StepNames.BUILD_LOGIC_AFTER_COMPILE);
        assertThat(after.requires()).isNotEmpty(); // mainCompile at minimum

        // BEFORE_PACKAGE is not level-0: needs resources + tests.
        assertThat(before.requires())
                .contains(StepNames.COPY_RESOURCES, StepNames.RUN_TESTS)
                .doesNotContain(StepNames.PARSE_BUILD);

        // package-jar waits on BEFORE_PACKAGE (not only resources).
        assertThat(packageJar.requires()).contains(StepNames.BUILD_LOGIC_BEFORE_PACKAGE);
    }

    @Test
    void before_package_skips_run_tests_when_skip_tests(@TempDir Path dir) throws Exception {
        Path project = scaffold(dir);
        Pipeline p = pipeline(project, dir.resolve("cache"), true);
        Map<String, Step> byName = index(p);

        assertThat(byName).containsKey(StepNames.BUILD_LOGIC_BEFORE_PACKAGE);
        assertThat(byName).doesNotContainKey(StepNames.RUN_TESTS);
        assertThat(byName.get(StepNames.BUILD_LOGIC_BEFORE_PACKAGE).requires())
                .contains(StepNames.COPY_RESOURCES)
                .doesNotContain(StepNames.RUN_TESTS);
        assertThat(byName.get(StepNames.PACKAGE_JAR).requires()).contains(StepNames.BUILD_LOGIC_BEFORE_PACKAGE);
    }

    private static Map<String, Step> index(Pipeline p) {
        return p.steps().stream().collect(Collectors.toMap(Step::name, Function.identity(), (a, b) -> a));
    }

    private static Path scaffold(Path dir) throws Exception {
        Path project = dir.resolve("proj");
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("jk.toml"), """
                [project]
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

    private static Pipeline pipeline(Path project, Path cache, boolean skipTests) {
        FilesCreateCache(cache);
        BuildPipelines.Inputs in = new BuildPipelines.Inputs(
                project,
                cache,
                project.resolve("jk.toml"),
                project.resolve("jk.lock"),
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
                cc.jumpkick.config.SessionContext.current());
        return BuildPipelines.coreBuilder(in).build();
    }

    private static void FilesCreateCache(Path cache) {
        try {
            Files.createDirectories(cache);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
