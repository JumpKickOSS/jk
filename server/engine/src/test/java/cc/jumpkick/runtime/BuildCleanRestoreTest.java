// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.task.ActionCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A fully-cached forecast is only actionable when the outputs it promises exist. After
 * {@code jk clean} every step can predict CACHED (keys survive the wipe by design) — the
 * module must still schedule so the build restores {@code target/} from cache instead of
 * skipping the module and leaving workspace links dangling.
 *
 * <p>Network test (Maven Central for the launcher/junit pins); the CAS persists under
 * build/ so repeat runs are warm.
 */
@Tag("integration")
class BuildCleanRestoreTest {

    private static final String REPOS = """
            [repositories]
            central = "https://repo.maven.apache.org/maven2/"
            """;

    // No tests: the exact shape that used to forecast fully-CACHED after clean and skip.
    private static final String NO_TEST_MANIFEST = """
            name    = "cleanlib"
            group   = "com.example"
            version = "1.0.0"
            jdk     = 25
            java    = 25

            # This project runs no tests; owning [test-dependencies] keeps the injected
            # junit-jupiter "latest" out of the graph and the launcher pin keeps the lock
            # deterministic (see KotlinSerializationTest).
            [test-dependencies]
            junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.3" }

            """ + REPOS;

    @Test
    void no_test_module_restores_wiped_outputs_on_clean_build(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("cleanlib"));
        Path cache = Path.of(System.getProperty("user.dir"), "build", "clean-restore-cache");
        Files.writeString(project.resolve("jk.toml"), NO_TEST_MANIFEST);
        Path src = Files.createDirectories(project.resolve("src/com/example"));
        Files.writeString(src.resolve("Lib.java"), """
                package com.example;

                public class Lib {
                    public static String greet() { return "hi"; }
                }
                """);

        JkBuild parsed = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(project, parsed);
        Session nested = Session.defaults().withCacheDir(cache);
        run(nested, () -> {
            BuildPlan lock = LockPlans.lockBuildPlan(
                    project, parsed, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
            assertThat(lock.run().errors()).isEmpty();

            BuildPlanResult first = BuildPlanner.coreBuilder(inputs(project, cache, nested, false))
                    .build()
                    .run();
            assertThat(first.errors()).isEmpty();
            assertThat(Files.isRegularFile(layout.mainJar())).isTrue();

            // jk clean.
            deleteTree(project.resolve("target"));

            // The wiped module must forecast dirty (restore), not fully cached + skipped.
            BuildGraph.Result graph = BuildGraph.resolve(project, parsed);
            Cas cas = JkStores.storeCas();
            ActionCache actionCache = new ActionCache(cas, cache.resolve("actions"));
            List<TaskForecast.Module> plan = TaskForecaster.of(graph, cas, actionCache, cache, false);
            assertThat(plan).hasSize(1);
            assertThat(plan.get(0).dirty())
                    .as("wiped no-test module must schedule so the build restores target/")
                    .isTrue();
            // When everything else is cache-clean the schedule reason is the restore gate.
            if (plan.get(0).steps().stream()
                    .filter(s -> !s.name().equals("restore-outputs"))
                    .allMatch(TaskForecast.Task::cached)) {
                assertThat(plan.get(0).steps())
                        .extracting(TaskForecast.Task::name)
                        .contains("restore-outputs");
            }

            // The scheduled build restores the outputs from cache.
            BuildPlanResult second = BuildPlanner.coreBuilder(inputs(project, cache, nested, false))
                    .build()
                    .run();
            assertThat(second.errors()).isEmpty();
            assertThat(Files.isRegularFile(layout.mainJar()))
                    .as("clean → build must repopulate target/ for a fully-cached module")
                    .isTrue();
            try (var walk = Files.walk(layout.classesDir())) {
                assertThat(walk.anyMatch(Files::isRegularFile)).isTrue();
            }
        });
    }

    @Test
    void skip_tests_module_with_tests_restores_wiped_outputs(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("cleanapp"));
        Path cache = Path.of(System.getProperty("user.dir"), "build", "clean-restore-cache");
        Files.writeString(project.resolve("jk.toml"), """
                name    = "cleanapp"
                group   = "com.example"
                version = "1.0.0"
                jdk     = 25
                java    = 25

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "=6.1.3" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.3" }

                """ + REPOS);
        Path src = Files.createDirectories(project.resolve("src/com/example"));
        Files.writeString(src.resolve("App.java"), """
                package com.example;

                public class App {
                    public static int one() { return 1; }
                }
                """);
        Path test = Files.createDirectories(project.resolve("test/src/com/example"));
        Files.writeString(test.resolve("AppTest.java"), """
                package com.example;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                class AppTest {
                    @Test
                    void one() {
                        assertEquals(1, App.one());
                    }
                }
                """);

        JkBuild parsed = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(project, parsed);
        Session nested = Session.defaults().withCacheDir(cache);
        run(nested, () -> {
            BuildPlan lock = LockPlans.lockBuildPlan(
                    project, parsed, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
            assertThat(lock.run().errors()).isEmpty();

            BuildPlanResult first = BuildPlanner.coreBuilder(inputs(project, cache, nested, true))
                    .build()
                    .run();
            assertThat(first.errors()).isEmpty();
            assertThat(Files.isRegularFile(layout.mainJar())).isTrue();

            deleteTree(project.resolve("target"));

            // Under --skip-tests the TestStamp escape hatch (its key fingerprints the missing
            // classes dir) is gone, so only the restore gate schedules the module.
            BuildGraph.Result graph = BuildGraph.resolve(project, parsed);
            Cas cas = JkStores.storeCas();
            ActionCache actionCache = new ActionCache(cas, cache.resolve("actions"));
            List<TaskForecast.Module> plan = TaskForecaster.of(graph, cas, actionCache, cache, true);
            assertThat(plan).hasSize(1);
            assertThat(plan.get(0).dirty()).isTrue();

            BuildPlanResult second = BuildPlanner.coreBuilder(inputs(project, cache, nested, true))
                    .build()
                    .run();
            assertThat(second.errors()).isEmpty();
            assertThat(Files.isRegularFile(layout.mainJar())).isTrue();
        });
    }

    /** {@link SessionContext#runWhere} with checked exceptions allowed in the body. */
    private static void run(Session session, ThrowingBody body) {
        SessionContext.runWhere(session, () -> {
            try {
                body.run();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @FunctionalInterface
    private interface ThrowingBody {
        void run() throws Exception;
    }

    private static BuildPlanner.Inputs inputs(Path project, Path cache, Session session, boolean skipTests) {
        return new BuildPlanner.Inputs(
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
                session);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
