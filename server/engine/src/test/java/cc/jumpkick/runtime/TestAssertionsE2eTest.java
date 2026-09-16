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
 * The forked test JVM runs with {@code -ea}, as Surefire's and Gradle's do: a Java {@code assert} in
 * a test is a check that fails the test. {@code [test] assertions = false} is the opt-out, and it is
 * a run-tests input, so flipping it re-runs the suite.
 */
// Out of the unit tier: network resolve of JUnit + two forked test JVMs.
@Tag("integration")
class TestAssertionsE2eTest {

    @Test
    void an_assert_statement_fails_the_test_unless_the_module_turns_assertions_off(@TempDir Path tmp) throws Exception {
        Path cache = TestCaches.dir("android-spike-cache");

        BuildPlanResult withAssertions = run(tmp.resolve("on"), cache, "");
        assertThat(withAssertions.success())
                .as("`assert false` fails under -ea")
                .isFalse();
        assertThat(withAssertions.errors()).anySatisfy(d -> {
            assertThat(d.className()).isEqualTo("com.example.AssertingTest");
            assertThat(d.exceptionClass()).isEqualTo("java.lang.AssertionError");
            assertThat(d.message()).contains("assertions are on");
        });

        BuildPlanResult withoutAssertions = run(tmp.resolve("off"), cache, """

                [test]
                assertions = false
                """);
        assertThat(withoutAssertions.errors()).isEmpty();
        assertThat(withoutAssertions.success())
                .as("the same `assert false` is inert without -ea")
                .isTrue();
    }

    private static BuildPlanResult run(Path project, Path cache, String testTable) throws Exception {
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), """
                name    = "asserting"
                group   = "com.example"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """ + testTable);
        Path test = Files.createDirectories(project.resolve("test/src/com/example"));
        Files.writeString(test.resolve("AssertingTest.java"), """
                package com.example;

                import org.junit.jupiter.api.Test;

                class AssertingTest {
                    @Test
                    void assertsFalse() {
                        assert false : "assertions are on";
                    }
                }
                """);

        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
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
        return BuildPlanner.fullPlan(in).run();
    }
}
