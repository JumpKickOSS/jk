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
import cc.jumpkick.test.RunResults;
import cc.jumpkick.testing.TestCaches;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A JUnit 4 suite runs under {@code jk test} with nothing but {@code junit:junit} declared: the lock
 * injects the Vintage engine beside the launcher, discovery finds the {@code @org.junit.Test}
 * methods, and each one is reported by class and method as a Jupiter test is — the passing one
 * counted, the failing one named.
 */
// Out of the unit tier: network resolve of junit4/vintage + a forked test JVM.
@Tag("integration")
class JUnit4VintageTest {

    @Test
    void junit4_tests_run_through_the_injected_vintage_engine(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("j4"));
        Path cache = TestCaches.dir("android-spike-cache");

        Files.writeString(project.resolve("jk.toml"), """
                name    = "j4"
                group   = "com.example"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit = { group = "junit", name = "junit", version = "=4.13.2" }

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

                import static org.junit.Assert.assertEquals;
                import org.junit.Test;

                public class AdderTest {
                    @Test
                    public void adds() {
                        assertEquals(4, Adder.add(2, 2));
                    }

                    @Test
                    public void addsWrong() {
                        assertEquals(5, Adder.add(2, 2));
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
                .contains("org.junit.vintage:junit-vintage-engine:jar:")
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
        RunResults results = new RunResults();
        RunResults.open(results);
        BuildPlanResult result;
        try {
            result = BuildPlanner.fullPlan(in).run();
        } finally {
            RunResults.close();
        }

        assertThat(result.success()).as("one of the two JUnit 4 tests fails").isFalse();
        assertThat(result.errors())
                .as("the failing method is named by class and method, as a Jupiter failure is")
                .anySatisfy(d -> {
                    assertThat(d.className()).isEqualTo("com.example.AdderTest");
                    assertThat(d.method()).isEqualTo("addsWrong");
                    assertThat(d.engine()).isEqualTo("junit-vintage");
                });

        List<MarkdownTestReport.Entry> entries = results.takeTests().stream()
                .flatMap(run -> run.entries().stream())
                .toList();
        assertThat(entries)
                .as("the results file gets one row per test, each under its class")
                .allSatisfy(e -> assertThat(e.className()).isEqualTo("com.example.AdderTest"))
                .extracting(MarkdownTestReport.Entry::displayName, MarkdownTestReport.Entry::isPass)
                .containsExactlyInAnyOrder(tuple("adds", true), tuple("addsWrong", false));
    }
}
