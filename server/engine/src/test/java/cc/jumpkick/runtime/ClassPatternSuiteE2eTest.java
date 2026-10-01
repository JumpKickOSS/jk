// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A suite selected by class pattern over the default suite's sources: plain {@code jk test} leaves
 * {@code FooIT} out, {@code --suite integration} runs it alone, {@code --guard} runs both, and a
 * {@code --class FooIT} under the default suite names the suite it belongs to.
 */
// Out of the unit tier: network resolve + real forked test JVMs.
@Tag("integration")
class ClassPatternSuiteE2eTest {

    @TempDir
    static Path tmp;

    private static Path project;

    @BeforeAll
    static void project() throws Exception {
        project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                [test.suites.integration]
                classes = ["IT*", "*IT", "*ITCase"]

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(project.resolve("src/com/example"));
        Files.writeString(project.resolve("src/com/example/App.java"), """
                package com.example;
                public final class App {
                    public static int twice(int n) { return n * 2; }
                }
                """);
        Path tests = Files.createDirectories(project.resolve("test/src/com/example"));
        Files.writeString(tests.resolve("FooTest.java"), testClass("FooTest", 1));
        Files.writeString(tests.resolve("FooIT.java"), testClass("FooIT", 2));
        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, tmp.resolve("cache"), null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();
    }

    @Test
    void plain_jk_test_runs_the_unit_class_and_leaves_the_integration_class_out() throws Exception {
        Run run = run(TestSelection.DEFAULT);
        assertThat(run.result().success()).isTrue();
        assertThat(run.tests().total())
                .as("FooTest's one test, not FooIT's two")
                .isEqualTo(1L);
    }

    @Test
    void the_integration_suite_runs_only_its_classes_from_the_same_compile() throws Exception {
        Run run = run(TestSelection.of(List.of("integration"), false, List.of(), List.of()));
        assertThat(run.result().success()).isTrue();
        assertThat(run.tests().total()).as("FooIT's two tests").isEqualTo(2L);
    }

    @Test
    void the_guard_runs_both_suites() throws Exception {
        Run run = run(TestSelection.of(List.of("test", "integration"), false, List.of(), List.of(), false, true));
        assertThat(run.result().success()).isTrue();
        assertThat(run.tests().total()).isEqualTo(3L);
    }

    @Test
    void a_class_of_the_integration_suite_named_under_the_default_suite_says_where_it_lives() throws Exception {
        Run run = run(TestSelection.DEFAULT.withClasses(List.of("FooIT")));
        assertThat(run.result().success()).isFalse();
        assertThat(run.tests().failures())
                .singleElement()
                .extracting(f -> f.message())
                .isEqualTo("no test classes matched --class FooIT — FooIT is in the integration suite"
                        + " (jk test --suite integration --class FooIT)");
    }

    private record Run(BuildPlan plan, BuildPlanResult result) {
        TestSummary tests() {
            return plan.get(BuildPlanner.TEST_RESULT).orElseThrow();
        }
    }

    private static Run run(TestSelection selection) throws Exception {
        Session session = Session.defaults().withTestSelection(selection);
        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                project,
                tmp.resolve("cache"),
                project.resolve("jk.toml"),
                project.resolve("jk-lock.toml"),
                project,
                1,
                1,
                null,
                null,
                /* skipTests */ false,
                false,
                /* testOnly */ true,
                false,
                Set.of(),
                session);
        BuildPlan plan = BuildPlanner.coreBuilder(in).build();
        return new Run(plan, SessionContext.where(session, plan::run));
    }

    private static String testClass(String name, int tests) {
        StringBuilder methods = new StringBuilder();
        for (int i = 0; i < tests; i++) {
            methods.append("    @Test void twice").append(i).append("() { assertEquals(4, App.twice(2)); }\n");
        }
        return """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class %s {
                %s}
                """.formatted(name, methods);
    }
}
