// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.task.ActionCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk build --redo} reruns tests but must still store the green marker under the normal
 * content key — the next {@code jk explain} / build forecast has to see run-tests CACHED, not a
 * phantom retest (same parity contract as compile and package-jar).
 *
 * <p>Network test (Maven Central for the junit pins); the CAS persists under build/ so repeat
 * runs are warm.
 */
@Tag("integration")
class RebuildRunTestsMarkerTest {

    private static JkConfig rebuildConfig() {
        return JkConfig.empty().withRebuild(Optional.of(true));
    }

    @Test
    void rebuild_stores_the_green_test_marker_under_the_normal_key(@TempDir Path tmp) throws Exception {
        // Real-path the fixture so ActionKey.taskTag matches BuildGraph.canonicalPath
        // (/var/folders vs /private/var/folders on macOS).
        Path project = Files.createDirectories(tmp.resolve("markerapp")).toRealPath();
        Path cache = Path.of(System.getProperty("user.dir"), "build", "clean-restore-cache");
        Files.writeString(project.resolve("jk.toml"), """
                name    = "markerapp"
                group   = "com.example"
                version = "1.0.0"
                jdk     = 25
                java    = 25
                layout  = "simple"

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "=6.1.1" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.1" }

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
        // Whole run under a REBUILD session: tests rerun, but the marker must still land
        // under the normal content key.
        Session rebuild = Session.defaults().withConfig(rebuildConfig()).withCacheDir(cache);
        SessionContext.runWhere(rebuild, () -> {
            try {
                BuildPlan lock = LockPlans.lockBuildPlan(
                        project, parsed, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
                assertThat(lock.run().errors()).isEmpty();

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
                        rebuild);
                // Core + tails, like jk build: post-JK-2211 run-tests is a terminal-join leaf and
                // a core-only plan prunes the whole test branch (no run, no marker).
                BuildPlan.Builder builder = BuildPlanner.coreBuilder(inputs);
                BuildPlanner.appendDeclaredTails(builder, inputs);
                BuildPlanResult result = builder.build().run();
                assertThat(result.errors()).isEmpty();
                assertThat(result.success()).isTrue();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // A plain follow-up forecast must see run-tests CACHED — the marker parity contract.
        SessionContext.runWhere(Session.defaults().withCacheDir(cache), () -> {
            try {
                BuildGraph.Result graph = BuildGraph.resolve(project, parsed);
                Cas cas = JkStores.cas(cache);
                ActionCache actionCache = new ActionCache(JkStores.cacheCas(cache), cache.resolve("actions"));
                List<TaskForecast.Module> plan = TaskForecaster.of(graph, cas, actionCache, cache, false);
                assertThat(plan).hasSize(1);
                assertThat(plan.get(0).steps())
                        .as("run-tests marker stored under --redo → forecast sees CACHED")
                        .anyMatch(s -> s.name().equals("run-tests") && s.cached());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
