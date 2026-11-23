// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.orchestrator.TestRun;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The built-in {@code integration} suite: with no {@code [test.suites]} table, tests tagged
 * {@code integration} leave the default suite for it, while another tag ({@code smoke}) and a
 * Failsafe-style class name ({@code *IT}) leave a test where it is.
 */
// Out of the unit tier: network resolve + real forked test JVMs.
@Tag("integration")
class IntegrationConventionE2eTest {

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
        Files.writeString(tests.resolve("FooTest.java"), testClass("FooTest", "", "", "@Tag(\"smoke\")"));
        Files.writeString(tests.resolve("CartIT.java"), testClass("CartIT", "", ""));
        Files.writeString(
                tests.resolve("CheckoutTest.java"), testClass("CheckoutTest", "@Tag(\"integration\")", "", ""));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, tmp.resolve("cache"), null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();
    }

    @Test
    void plain_jk_test_leaves_integration_tagged_tests_out() throws Exception {
        assertThat(total(TestSelection.DEFAULT))
                .as("FooTest's two (smoke is no suite) and CartIT's one (*IT is no suite)")
                .isEqualTo(3L);
    }

    @Test
    void the_integration_suite_runs_them_with_no_table() throws Exception {
        assertThat(total(suites("integration"))).as("CheckoutTest's two").isEqualTo(2L);
    }

    @Test
    void the_guard_runs_both() throws Exception {
        assertThat(total(TestSelection.of(List.of(), false, List.of(), List.of(), true, false, false)))
                .isEqualTo(5L);
    }

    @Test
    void a_tag_with_no_table_is_no_suite() throws Exception {
        Run run = run(suites("smoke"));
        assertThat(run.result().success()).isFalse();
    }

    private static TestSelection suites(String... names) {
        return TestSelection.of(List.of(names), false, List.of(), List.of());
    }

    private static long total(TestSelection selection) throws Exception {
        Run run = run(selection);
        assertThat(run.result().success()).as("%s", run.result().errors()).isTrue();
        return run.tests().total();
    }

    private record Run(BuildPlan plan, BuildPlanResult result) {
        TestSummary tests() {
            return plan.get(BuildPlanner.TEST_RUN).map(TestRun::result).orElseThrow();
        }
    }

    private static Run run(TestSelection selection) throws Exception {
        Session session = Session.defaults().withTestSelection(selection);
        BuildPlanner.Inputs in = TestInputs.of(project, tmp.resolve("cache"), session)
                .withEstimatedTestCount(1)
                .withTestOnly(true);
        BuildPlan plan = BuildPlanner.coreBuilder(in).build();
        return new Run(plan, SessionContext.where(session, plan::run));
    }

    /** A test class under {@code classTags}, one test per entry of {@code methodTags}. */
    private static String testClass(String name, String classTags, String... methodTags) {
        StringBuilder methods = new StringBuilder();
        for (int i = 0; i < methodTags.length; i++) {
            methods.append("    ")
                    .append(methodTags[i])
                    .append(" @Test void twice")
                    .append(i)
                    .append("() { assertEquals(4, App.twice(2)); }\n");
        }
        return """
                package com.example;
                import org.junit.jupiter.api.Tag;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                %s
                class %s {
                %s}
                """.formatted(classTags, name, methods);
    }
}
