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
 * counted, the failing one named. The suite's temp root is outside the project, in its real spelling.
 */
// Out of the unit tier: network resolve of junit4/vintage + a forked test JVM.
@Tag("integration")
class JUnit4VintageTest {

    private static final String MANIFEST = """
            name    = "j4"
            group   = "com.example"
            version = "1.0.0"
            java    = 25

            [test-dependencies]
            junit = { group = "junit", name = "junit", version = "=4.13.2" }

            [repositories]
            central = "https://repo.maven.apache.org/maven2/"
            """;

    @Test
    void junit4_tests_run_through_the_injected_vintage_engine(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("j4"));
        Files.writeString(project.resolve("jk.toml"), MANIFEST);
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

                    @Test
                    public void tempRootIsOutsideTheProject() throws Exception {
                        java.nio.file.Path tmp = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
                        java.nio.file.Path cwd = java.nio.file.Path.of("").toRealPath();
                        assertEquals(false, tmp.toRealPath().startsWith(cwd));
                        assertEquals(tmp.toRealPath(), tmp);
                    }
                }
                """);

        RunResults results = new RunResults();
        BuildPlanResult result = lockAndTest(project, results);

        assertThat(result.success()).as("one of the two JUnit 4 tests fails").isFalse();
        assertThat(result.errors())
                .as("the failing method is named by class and method, as a Jupiter failure is")
                .anySatisfy(d -> {
                    assertThat(d.className()).isEqualTo("com.example.AdderTest");
                    assertThat(d.method()).isEqualTo("addsWrong");
                    assertThat(d.engine()).isEqualTo("junit-vintage");
                });

        List<MarkdownTestReport.Entry> entries = entries(results);
        assertThat(entries)
                .as("the results file gets one row per test, each under its class")
                .allSatisfy(e -> assertThat(e.className()).isEqualTo("com.example.AdderTest"))
                .extracting(MarkdownTestReport.Entry::displayName, MarkdownTestReport.Entry::isPass)
                .containsExactlyInAnyOrder(
                        tuple("adds", true), tuple("addsWrong", false), tuple("tempRootIsOutsideTheProject", true));
    }

    /**
     * A JUnit 3 {@code suite()} whose tests are a nested {@code TestCase} with no {@code
     * TestCase(String)} constructor: the suite runs them, and the nested class is not run on its
     * own, which would fail with {@code has no public constructor TestCase(String name)}.
     */
    @Test
    void a_suite_methods_nested_runner_is_not_run_as_a_class_of_its_own(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("j4"));
        Files.writeString(project.resolve("jk.toml"), MANIFEST);
        Path test = Files.createDirectories(project.resolve("test/src/com/example"));
        Files.writeString(test.resolve("SizesTest.java"), """
                package com.example;

                import junit.framework.TestCase;
                import junit.framework.TestSuite;

                public class SizesTest {
                    public static TestSuite suite() {
                        TestSuite suite = new TestSuite();
                        for (int size : new int[] {1, 2}) suite.addTest(new SizesTestRunner("positive", size));
                        return suite;
                    }

                    public static class SizesTestRunner extends TestCase {
                        private final int size;

                        public SizesTestRunner(String method, int size) {
                            super(method);
                            this.size = size;
                        }

                        public void positive() {
                            assertTrue(size > 0);
                        }
                    }
                }
                """);

        RunResults results = new RunResults();
        BuildPlanResult result = lockAndTest(project, results);

        assertThat(result.success()).as("%s", result.errors()).isTrue();
        assertThat(entries(results))
                .as("both suite members ran and passed; no warning test for the nested class")
                .hasSize(2)
                .allSatisfy(e -> assertThat(e.isPass()).isTrue());
    }

    /** Locks {@code project}, asserting the lock carries Vintage, then builds it with its tests run. */
    private static BuildPlanResult lockAndTest(Path project, RunResults results) throws Exception {
        Path cache = TestCaches.dir("android-spike-cache");
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
        RunResults.open(results);
        try {
            return BuildPlanner.fullPlan(in).run();
        } finally {
            RunResults.close();
        }
    }

    private static List<MarkdownTestReport.Entry> entries(RunResults results) {
        return results.takeTests().stream()
                .flatMap(run -> run.entries().stream())
                .toList();
    }
}
