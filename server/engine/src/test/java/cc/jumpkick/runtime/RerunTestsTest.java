// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskForecast;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.runtime.workspace.ForecastWalk;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.testing.TestCaches;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk test --rerun} runs a suite its green marker would replay, and leaves the compiles that
 * feed it cached: the forecast prices the suite as a run and the compiles as cached, and the run
 * writes the suite's reports again without rewriting a class.
 *
 * <p>Network test (Maven Central for the junit pins); the CAS persists under build/ so repeat
 * runs are warm.
 */
@Tag("integration")
class RerunTestsTest {

    @Test
    void a_rerun_runs_the_suite_and_keeps_the_compiles_cached(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("rerunapp")).toRealPath();
        Path cache = TestCaches.dir("rerun-tests-cache");
        Files.writeString(project.resolve("jk.toml"), """
                name    = "rerunapp"
                group   = "com.example"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path src = Files.createDirectories(project.resolve("src/com/example"));
        Files.writeString(src.resolve("M.java"), """
                package com.example;

                public class M {
                    public static int two() { return 2; }
                }
                """);
        Path test = Files.createDirectories(project.resolve("test/src/com/example"));
        Files.writeString(test.resolve("MTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class MTest {
                    @Test
                    void two() {
                        assertEquals(2, M.two());
                    }
                }
                """);
        JkBuild parsed = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(project, parsed);
        Session plain = Session.defaults().withCacheDir(cache);
        Session rerun = plain.withRerunTests(true);

        build(project, cache, plain, true);
        assertThat(runTests(forecast(project, parsed, cache, plain)).cached())
                .as("a green suite over an unchanged tree replays")
                .isTrue();

        List<TaskForecast.Task> steps = forecast(project, parsed, cache, rerun);
        List<TaskForecast.Task> compiles =
                steps.stream().filter(s -> s.name().startsWith("compile")).toList();
        assertThat(compiles).as("the steps: %s", steps).hasSizeGreaterThanOrEqualTo(2);
        assertThat(compiles).extracting(TaskForecast.Task::name).contains(TaskNames.COMPILE_TEST);
        assertThat(compiles).as("a re-run leaves the compiles cached").allMatch(TaskForecast.Task::cached);
        assertThat(runTests(steps).cached()).isFalse();
        assertThat(runTests(steps).text()).contains("rerun");

        Path reports = layout.testResultsDir();
        PathUtil.deleteRecursively(reports);
        Path compiled = layout.classesDir().resolve("com/example/M.class");
        FileTime before = Files.getLastModifiedTime(compiled);
        build(project, cache, rerun, false);
        assertThat(reports.resolve("TEST-com.example.MTest.xml"))
                .as("the suite ran again")
                .isRegularFile();
        assertThat(Files.getLastModifiedTime(compiled))
                .as("the main classes were not compiled again")
                .isEqualTo(before);
    }

    private static void build(Path project, Path cache, Session session, boolean lock) {
        SessionContext.runWhere(session, () -> {
            try {
                if (lock) {
                    BuildPlan locked = LockPlans.lockBuildPlan(
                            project, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
                    assertThat(locked.run().errors()).isEmpty();
                }
                BuildPlanner.Inputs inputs =
                        TestInputs.of(project, cache, session).withEstimatedTestCount(1);
                BuildPlan.Builder builder = BuildPlanner.coreBuilder(inputs);
                PlannerTails.appendDeclaredTails(builder, inputs);
                BuildPlanResult result = builder.build().run();
                assertThat(result.errors()).isEmpty();
                assertThat(result.success()).isTrue();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static List<TaskForecast.Task> forecast(Path project, JkBuild parsed, Path cache, Session session)
            throws Exception {
        return SessionContext.where(session, () -> {
            BuildGraph.Result graph = BuildGraph.resolve(project, parsed);
            Cas cas = JkStores.storeCas();
            ActionCache actionCache = new ActionCache(JkStores.cacheCas(cache), cache.resolve("actions"));
            List<TaskForecast.Module> plan = ForecastWalk.of(graph, cas, actionCache, cache, false);
            assertThat(plan).hasSize(1);
            return plan.get(0).steps();
        });
    }

    private static TaskForecast.Task runTests(List<TaskForecast.Task> steps) {
        return steps.stream()
                .filter(s -> s.name().equals(TaskNames.RUN_TESTS))
                .findFirst()
                .orElseThrow();
    }
}
