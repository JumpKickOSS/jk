// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.testing.TestCaches;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A green run with zero tests is never the answer. The injected launcher follows the declared
 * Jupiter's Platform line, so a Jupiter 5 suite locks a Platform 1 launcher and runs; and a suite
 * whose classes hold no test fails the run-tests step with the class count instead of reporting
 * success.
 */
// Out of the unit tier: network resolve of JUnit + forked test JVMs.
@Tag("integration")
class JupiterLineE2eTest {

    private static final String REPOSITORIES = """

            [repositories]
            central = "https://repo.maven.apache.org/maven2/"
            """;

    @Test
    void an_old_jupiter_locks_a_launcher_on_its_own_platform_line_and_runs(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("old-jupiter");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), """
                name    = "old-jupiter"
                group   = "com.example"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "5.9.0" }  # off the train: an old Jupiter line is the point of this fixture
                """ + REPOSITORIES);
        Path test = Files.createDirectories(project.resolve("test/src/com/example"));
        Files.writeString(test.resolve("OldTest.java"), """
                package com.example;

                import org.junit.jupiter.api.Assertions;
                import org.junit.jupiter.api.Test;

                class OldTest {
                    @Test
                    void runs() {
                        Assertions.assertTrue(true);
                    }
                }
                """);

        BuildPlan plan = lockAndPlan(project);
        BuildPlanResult result = plan.run();
        assertThat(Files.readString(project.resolve("jk-lock.toml")))
                .as("the launcher rides Platform 1 beside Jupiter 5")
                .contains("org.junit.platform:junit-platform-launcher:jar:\"\nversion  = \"1.9.0\"");
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        TestSummary tests = plan.get(BuildPlanner.TEST_RESULT).orElseThrow();
        assertThat(tests.total()).as("the Jupiter 5 engine ran its test").isEqualTo(1);
        assertThat(tests.succeeded()).isEqualTo(1);
    }

    @Test
    void a_suite_whose_classes_hold_no_test_fails_the_step_with_the_class_count(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("no-tests");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), """
                name    = "no-tests"
                group   = "com.example"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                """ + REPOSITORIES);
        Path test = Files.createDirectories(project.resolve("test/src/com/example"));
        Files.writeString(test.resolve("HelperOnly.java"), """
                package com.example;

                class HelperOnly {
                    static int answer() {
                        return 42;
                    }
                }
                """);

        BuildPlan plan = lockAndPlan(project);
        BuildPlanResult result = plan.run();
        assertThat(result.success())
                .as("zero tests discovered is a failed step, never OK")
                .isFalse();
        assertThat(result.errors()).anySatisfy(d -> assertThat(d.step()).isEqualTo("run-tests"));
        TestSummary tests = plan.get(BuildPlanner.TEST_RESULT).orElseThrow();
        assertThat(tests.failed()).isEqualTo(1);
        assertThat(tests.failures()).anySatisfy(f -> {
            assertThat(f.method()).isEqualTo("(test run)");
            assertThat(f.message()).contains("no tests discovered in 1 class");
        });
    }

    private static BuildPlan lockAndPlan(Path project) throws Exception {
        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        Path cache = TestCaches.dir("android-spike-cache");
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).isTrue();
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
        return BuildPlanner.fullPlan(in);
    }
}
