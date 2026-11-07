// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static cc.jumpkick.runtime.KspProjects.build;
import static cc.jumpkick.runtime.KspProjects.forecast;
import static cc.jumpkick.runtime.KspProjects.lock;
import static cc.jumpkick.runtime.KspProjects.step;
import static cc.jumpkick.runtime.KspProjects.write;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.testing.TestCaches;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk explain} and the build agree on compile-test for a module whose tests are Kotlin and
 * Java, the Java reading a Kotlin test type. The build's javac reads the Kotlin test output, so the
 * forecast keys javac on the same classpath, that output included, as the Kotlin test compile's
 * record restores it after {@code jk clean}.
 *
 * <p>Network test (Maven Central, for Kotlin and JUnit).
 */
@Tag("slow")
class MixedTestForecastParityTest {

    private static final String MANIFEST = """
            name    = "mixed"
            group   = "com.example"
            version = "1.0.0"
            java    = 25
            kotlin  = "^2.4.10"

            [test-dependencies]
            junit-jupiter = "6.1.3"

            [repositories]
            central = "https://repo.maven.apache.org/maven2/"
            """;

    @Test
    void on_a_warm_tree_the_forecast_and_the_build_agree_on_compile_test(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("mixed-test-forecast");
        Path project = mixedProject(tmp, cache);
        assertThat(build(project, cache).errors()).isEmpty();

        Map<String, TaskForecast.Task> forecast = forecast(project, cache, false);
        BuildPlanResult rebuilt = build(project, cache);

        assertThat(rebuilt.errors()).isEmpty();
        assertAgree(forecast, rebuilt, TaskNames.COMPILE_TEST, TaskNames.RUN_TESTS);
    }

    @Test
    void after_a_clean_the_forecast_and_the_build_agree_on_compile_test(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("mixed-test-forecast");
        Path project = Files.createDirectories(tmp.resolve("mixed"));
        BuildLayout layout = writeMixedProject(project, cache);
        BuildPlanResult first = build(project, cache);
        assertThat(first.errors()).isEmpty();
        assertThat(step(first, TaskNames.RUN_TESTS).status()).isEqualTo(TaskStatus.SUCCESS);

        PathUtil.deleteRecursively(layout.buildDir());
        Map<String, TaskForecast.Task> forecast = forecast(project, cache, false);
        BuildPlanResult rebuilt = build(project, cache);

        assertThat(rebuilt.errors()).isEmpty();
        assertAgree(forecast, rebuilt, TaskNames.COMPILE_KOTLIN, TaskNames.COMPILE_TEST, TaskNames.RUN_TESTS);
    }

    /** Each step the build restored is one the forecast said would come from the cache. */
    private static void assertAgree(Map<String, TaskForecast.Task> forecast, BuildPlanResult build, String... steps) {
        for (String name : steps) {
            TaskForecast.Task predicted = requireNonNull(forecast.get(name), () -> "no " + name + " in " + forecast);
            assertThat(step(build, name).status())
                    .as("%s: the build restores what the forecast said it would (%s)", name, predicted.text())
                    .isEqualTo(TaskStatus.SKIPPED);
            assertThat(predicted.cached())
                    .as("%s: the forecast keys what the build keys: %s", name, predicted.text())
                    .isTrue();
        }
    }

    /** Kotlin main code; a Kotlin test helper and a Java test that reads it. */
    private static Path mixedProject(Path tmp, Path cache) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("mixed"));
        writeMixedProject(project, cache);
        return project;
    }

    /** Kotlin main code; a Kotlin test helper and a Java test that reads it; locked. */
    private static BuildLayout writeMixedProject(Path project, Path cache) throws Exception {
        write(project.resolve("src/main/kotlin/demo/Greeter.kt"), """
                package demo

                class Greeter {
                    fun name() = "Greeter"
                }
                """);
        write(project.resolve("src/test/kotlin/demo/Fixtures.kt"), """
                package demo

                object Fixtures {
                    @JvmStatic
                    fun expected() = "Greeter"
                }
                """);
        write(project.resolve("src/test/java/demo/GreeterTest.java"), """
                package demo;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class GreeterTest {
                    @Test
                    void greets() {
                        assertEquals(Fixtures.expected(), new Greeter().name());
                    }
                }
                """);
        return lock(project, MANIFEST, cache);
    }
}
