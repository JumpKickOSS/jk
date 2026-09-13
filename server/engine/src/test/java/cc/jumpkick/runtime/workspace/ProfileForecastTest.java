// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.BuildStamps;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.BuildPlanner;
import cc.jumpkick.runtime.LockPlans;
import cc.jumpkick.runtime.PlannerTails;
import cc.jumpkick.runtime.TaskForecaster;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.TaskForecast;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code --profile} whose javac args differ from the default's is a different compile. The
 * forecast keys its compile steps with the request's profile, so a profile build after a default
 * build schedules the module and recompiles rather than reporting the default record as a hit.
 *
 * <p>Network test (Maven Central for the launcher pin); the CAS persists under build/ so repeat
 * runs are warm.
 */
@Tag("integration")
class ProfileForecastTest {

    private static final String MANIFEST = """
            name    = "profiled"
            group   = "com.example"
            version = "1.0.0"
            java    = 25

            [profiles.strict]
            javac = ["-Werror"]

            # No tests here; owning [test-dependencies] keeps the injected junit-jupiter out of
            # the graph and the launcher pin keeps the lock deterministic.
            [test-dependencies]
            junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.3" }

            [repositories]
            central = "https://repo.maven.apache.org/maven2/"
            """;

    @Test
    void a_profile_build_after_a_default_build_recompiles(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("profiled")).toRealPath();
        Path cache = TestCaches.dir("clean-restore-cache");
        Files.writeString(project.resolve("jk.toml"), MANIFEST);
        Path src = Files.createDirectories(project.resolve("src/com/example"));
        Files.writeString(src.resolve("Lib.java"), """
                package com.example;

                public class Lib {
                    public static String greet() { return "hi"; }
                }
                """);
        JkBuild parsed = JkBuildParser.parse(project.resolve("jk.toml"));
        Session session = Session.defaults().withCacheDir(cache);

        SessionContext.runWhere(session, () -> {
            try {
                BuildPlan lock = LockPlans.lockBuildPlan(
                        project, parsed, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
                assertThat(lock.run().errors()).isEmpty();
                BuildGraph.Result graph = BuildGraph.resolve(project, parsed);

                BuildPlanResult defaultBuild = build(project, cache, session, null);
                assertThat(defaultBuild.success()).isTrue();
                // The freshness stamp is a separate fast path that reads sources, classpath and
                // release but not the javac args, and it answers before any key is computed on
                // both sides. Removing it leaves the action key — the path under test — to decide.
                Files.delete(BuildLayout.of(project, parsed).classesDir().resolve(BuildStamps.JAVA));
                assertThat(compileMain(forecast(graph, cache, null)).cached())
                        .as("the default build's compile is on record for the default profile")
                        .isTrue();
                assertThat(compileMain(forecast(graph, cache, "strict")).cached())
                        .as("the profile's -Werror keys a different compile")
                        .isFalse();
                BuildForecasting.Preflight preflight = BuildForecasting.forecastWithFingerprints(
                        graph, cache, false, project, WorkspaceTarget.PACKAGE, Set.of(), false, "strict");
                assertThat(preflight.dirty())
                        .as("the preflight schedules the module")
                        .contains(project);

                BuildPlanResult profileBuild = build(project, cache, session, "strict");
                assertThat(profileBuild.success()).isTrue();
                // The live plan's Java compile step; the forecast reports it as compile-main.
                assertThat(stepStatus(profileBuild, TaskNames.COMPILE_JAVA))
                        .as("the profile build compiles, it does not replay the default record")
                        .isEqualTo(TaskStatus.SUCCESS);
                assertThat(compileMain(forecast(graph, cache, "strict")).cached())
                        .isTrue();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static BuildPlanResult build(Path project, Path cache, Session session, @Nullable String profile) {
        BuildPlanner.Inputs inputs = new BuildPlanner.Inputs(
                project,
                cache,
                project.resolve("jk.toml"),
                project.resolve("jk-lock.toml"),
                project,
                1,
                0,
                profile,
                null,
                false,
                false,
                false,
                false,
                Set.of(),
                session);
        BuildPlan.Builder builder = BuildPlanner.coreBuilder(inputs);
        PlannerTails.appendDeclaredTails(builder, inputs);
        return builder.build().run();
    }

    private static List<TaskForecast.Module> forecast(BuildGraph.Result graph, Path cache, @Nullable String profile) {
        Cas cas = JkStores.storeCas();
        ActionCache actionCache = new ActionCache(JkStores.cacheCas(cache), cache.resolve("actions"));
        return TaskForecaster.of(graph, cas, actionCache, cache, false, WorkspaceTarget.PACKAGE, Set.of(), profile);
    }

    private static TaskForecast.Task compileMain(List<TaskForecast.Module> modules) {
        assertThat(modules).hasSize(1);
        return modules.getFirst().steps().stream()
                .filter(step -> step.name().equals(TaskNames.COMPILE_MAIN))
                .findFirst()
                .orElseThrow();
    }

    private static TaskStatus stepStatus(BuildPlanResult result, String step) {
        return result.steps().stream()
                .filter(s -> s.name().equals(step))
                .map(BuildPlanResult.StepReport::status)
                .findFirst()
                .orElseThrow();
    }
}
