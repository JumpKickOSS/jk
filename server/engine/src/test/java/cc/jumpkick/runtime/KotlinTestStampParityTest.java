// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.TaskForecast;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Kotlin suite's run-tests stamp folds the Kotlin test compile's action key, on the live path
 * and on the forecast path alike. The live run stores its green marker under a key that carries
 * the key of the {@code compile-test-kotlin} record; the forecast, which has no kotlinc predictor,
 * reads that record's key back and must land on the same stamp — otherwise {@code jk explain}
 * schedules a suite the build then replays, and the ETA prices a run that never happens.
 *
 * <p>Network test (Maven Central for the Kotlin compiler and JUnit); the CAS persists under
 * build/ so repeat runs are warm.
 */
@Tag("integration")
class KotlinTestStampParityTest {

    private static final String MANIFEST = """
            name    = "ktsuite"
            group   = "com.example"
            version = "1.0.0"
            java    = 25
            kotlin  = "^2.4.10"

            [test-dependencies]
            junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "=6.1.3" }
            junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.3" }

            [repositories]
            central = "https://repo.maven.apache.org/maven2/"
            """;

    @Test
    void the_forecast_and_the_build_stamp_a_kotlin_suite_under_the_same_key(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("ktsuite")).toRealPath();
        Path cache = TestCaches.dir("android-spike-cache");
        Files.writeString(project.resolve("jk.toml"), MANIFEST);
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/Lib.kt"), """
                package com.example
                class Lib {
                    fun greet(): String = "hi"
                }
                """);
        Files.createDirectories(project.resolve("test/src"));
        Files.writeString(project.resolve("test/src/LibTest.kt"), """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class LibTest {
                    @Test
                    fun greets() {
                        assertEquals("hi", Lib().greet())
                    }
                }
                """);
        JkBuild parsed = JkBuildParser.parse(project.resolve("jk.toml"));
        Session session = Session.defaults().withCacheDir(cache);

        SessionContext.runWhere(session, () -> {
            try {
                BuildPlan lock = LockPlans.lockBuildPlan(
                        project, parsed, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
                assertThat(lock.run().errors()).isEmpty();

                BuildPlanResult first = build(project, cache, session);
                assertThat(first.errors()).isEmpty();
                assertThat(stepStatus(first, TaskNames.RUN_TESTS))
                        .as("the first build runs the Kotlin suite")
                        .isEqualTo(TaskStatus.SUCCESS);

                // The Kotlin test compile is on record under its own id: the input the stamp folds.
                ActionCache actionCache = new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache));
                Path testClasses = BuildLayout.of(project, parsed).testClassesDir();
                assertThat(actionCache.lastFor(ActionKey.qualifiedTaskId(TaskNames.COMPILE_TEST_KOTLIN, testClasses)))
                        .as("compile-test-kotlin left an action record")
                        .isPresent();

                BuildGraph.Result graph = BuildGraph.resolve(project, parsed);
                List<TaskForecast.Module> modules = TaskForecaster.of(
                        graph, JkStores.storeCas(), actionCache, cache, false, WorkspaceTarget.PACKAGE, Set.of(), null);
                assertThat(modules).hasSize(1);
                TaskForecast.Task runTests = modules.getFirst().steps().stream()
                        .filter(step -> step.name().equals(TaskNames.RUN_TESTS))
                        .findFirst()
                        .orElseThrow();
                assertThat(runTests.cached())
                        .as("the forecast computes the live stamp, Kotlin compile key included: %s", runTests.text())
                        .isTrue();

                BuildPlanResult second = build(project, cache, session);
                assertThat(second.errors()).isEmpty();
                assertThat(stepStatus(second, TaskNames.RUN_TESTS))
                        .as("the second build replays the green marker the forecast found")
                        .isEqualTo(TaskStatus.SKIPPED);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static BuildPlanResult build(Path project, Path cache, Session session) {
        BuildPlanner.Inputs inputs = new BuildPlanner.Inputs(
                project,
                cache,
                project.resolve("jk.toml"),
                project.resolve("jk-lock.toml"),
                project,
                1,
                1,
                null,
                null,
                false,
                false,
                false,
                false,
                Set.of(),
                session);
        return BuildPlanner.fullPlan(inputs).run();
    }

    private static TaskStatus stepStatus(BuildPlanResult result, String step) {
        return result.steps().stream()
                .filter(s -> s.name().equals(step))
                .map(BuildPlanResult.StepReport::status)
                .findFirst()
                .orElseThrow();
    }
}
