// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.testing.TestCaches;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launcher pinned to the Platform 1 line beside Jupiter 6: the lock accepts it (the launcher's
 * own floor is met), the Jupiter engine cannot start under it, and the run fails as a launcher
 * failure that names the engine, both versions of the {@code org.junit.platform} line, the pin,
 * and the repair — not as one anonymous red test.
 */
// Out of the unit tier: network resolve of JUnit + a forked test JVM.
@Tag("integration")
class TestLauncherFailureE2eTest {

    @Test
    void a_pin_conflict_fails_the_step_and_names_the_engine_and_both_coordinates(@TempDir Path project)
            throws Exception {
        Path cache = TestCaches.dir("android-spike-cache");
        Files.writeString(project.resolve("jk.toml"), """
                name    = "pinned"
                group   = "com.example"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=1.13.4" }  # off the train: Platform 1 beside Jupiter 6

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path test = Files.createDirectories(project.resolve("test/src/com/example"));
        Files.writeString(test.resolve("PinnedTest.java"), """
                package com.example;

                import org.junit.jupiter.api.Test;

                class PinnedTest {
                    @Test
                    void passes() {}
                }
                """);

        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("the launcher pin resolves").isTrue();
        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
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
                SessionContext.current());
        BuildPlanResult result = BuildPlanner.fullPlan(in).run();

        assertThat(result.success()).isFalse();
        assertThat(result.errors())
                .as("the launcher failure is one diagnostic on the step, and no test is counted")
                .noneMatch(d -> "test-failure".equals(d.code()))
                .anySatisfy(d -> {
                    assertThat(d.code()).isEqualTo(TestLauncherReport.CODE);
                    assertThat(d.step()).isEqualTo("run-tests");
                    assertThat(d.message())
                            .contains("before any test ran")
                            .contains("junit-jupiter")
                            .contains("Two versions of the org.junit.platform line")
                            .contains("org.junit.platform:junit-platform-launcher (declared =1.13.4 in"
                                    + " [test-dependencies])")
                            .contains("6.1.3: org.junit.platform:junit-platform-commons")
                            .contains("jk why org.junit.platform:junit-platform-launcher");
                    assertThat(d.stack()).contains("jk-test-runner:");
                });
    }
}
