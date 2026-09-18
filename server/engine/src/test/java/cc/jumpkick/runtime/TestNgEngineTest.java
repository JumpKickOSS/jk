// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.test.MarkdownTestReport;
import cc.jumpkick.testing.TestCaches;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A TestNG suite runs under {@code jk test} with nothing but {@code org.testng:testng} declared: the
 * lock injects the TestNG engine beside the launcher, discovery finds the {@code
 * @org.testng.annotations.Test} methods, and each one is reported by class and method as a Jupiter
 * test is — the passing one counted, the failing one named.
 */
// Out of the unit tier: network resolve of testng and its engine + a forked test JVM.
@Tag("integration")
class TestNgEngineTest {

    @Test
    void testng_tests_run_through_the_injected_testng_engine(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("ng"));
        Path cache = TestCaches.dir("android-spike-cache");

        Files.writeString(project.resolve("jk.toml"), """
                name    = "ng"
                group   = "com.example"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                testng = { group = "org.testng", name = "testng", version = "7.12.0" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path src = Files.createDirectories(project.resolve("src/com/example"));
        Files.writeString(src.resolve("Adder.java"), """
                package com.example;

                public final class Adder {
                    public static int add(int a, int b) { return a + b; }
                }
                """);
        Path test = Files.createDirectories(project.resolve("test/src/com/example"));
        Files.writeString(test.resolve("AdderTest.java"), """
                package com.example;

                import static org.testng.Assert.assertEquals;
                import org.testng.annotations.Test;

                public class AdderTest {
                    @Test
                    public void adds() {
                        assertEquals(Adder.add(2, 2), 4);
                    }

                    @Test
                    public void addsWrong() {
                        assertEquals(Adder.add(2, 2), 5);
                    }
                }
                """);

        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).isTrue();
        String lockText = Files.readString(project.resolve("jk-lock.toml"));
        assertThat(lockText)
                .as("the lock carries the engine the declared framework needs")
                .contains("org.junit.support:testng-engine:jar:")
                .contains("org.junit.platform:junit-platform-launcher:jar:");

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
                false, // run the tests — that IS the assertion
                false,
                false,
                false,
                Set.of(),
                SessionContext.current());
        BuildPlanResult result = BuildPlanner.fullPlan(in).run();

        assertThat(result.success()).as("one of the two TestNG tests fails").isFalse();
        assertThat(result.errors())
                .as("the failing method is named by class and method, in the shape a Jupiter failure takes")
                .anySatisfy(d -> {
                    assertThat(d.className()).isEqualTo("com.example.AdderTest");
                    assertThat(d.method()).isEqualTo("addsWrong()");
                    assertThat(d.engine()).isEqualTo("testng");
                });

        List<MarkdownTestReport.Entry> entries = MarkdownTestReport.takeUnder(project).stream()
                .flatMap(run -> run.entries().stream())
                .toList();
        assertThat(entries)
                .as("the results file gets one row per test, each under its class")
                .allSatisfy(e -> assertThat(e.className()).isEqualTo("com.example.AdderTest"))
                .extracting(MarkdownTestReport.Entry::displayName, MarkdownTestReport.Entry::isPass)
                .containsExactlyInAnyOrder(tuple("adds()", true), tuple("addsWrong()", false));
    }
}
