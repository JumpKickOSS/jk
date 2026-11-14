// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.task.TestRun;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Suites that claim tests by tag: {@code integration} and {@code slow} take their tagged tests from
 * the default suite's sources and from the {@code e2e} directory alike, the default and {@code e2e}
 * suites keep what neither claims, and a test both claim runs where {@code exclude-tags} sends it.
 */
// Out of the unit tier: network resolve + real forked test JVMs.
@Tag("integration")
class TagSuiteE2eTest {

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
                tags         = ["integration"]
                exclude-tags = ["slow"]

                [test.suites.slow]
                tags = ["slow"]

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
        // One untagged test, one tagged slow by its method.
        Files.writeString(tests.resolve("FooTest.java"), testClass("FooTest", "", "", "@Tag(\"slow\")"));
        Files.writeString(tests.resolve("CartTest.java"), testClass("CartTest", "@Tag(\"integration\")", "", ""));
        Files.writeString(
                tests.resolve("HeavyTest.java"), testClass("HeavyTest", "@Tag(\"integration\") @Tag(\"slow\")", ""));
        Path e2e = Files.createDirectories(project.resolve("e2e/src/com/example"));
        Files.writeString(e2e.resolve("CheckoutE2e.java"), testClass("CheckoutE2e", "", "", "@Tag(\"integration\")"));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, tmp.resolve("cache"), null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();
    }

    @Test
    void the_default_suite_runs_what_no_suite_claims() throws Exception {
        assertThat(total(TestSelection.DEFAULT)).as("FooTest's untagged test").isEqualTo(1L);
    }

    @Test
    void a_tag_suite_runs_its_tagged_tests_from_every_source_less_its_exclusions() throws Exception {
        assertThat(total(suites("integration")))
                .as("CartTest's two, CheckoutE2e's tagged one; HeavyTest is slow's")
                .isEqualTo(3L);
        assertThat(total(suites("slow")))
                .as("FooTest's slow method and HeavyTest")
                .isEqualTo(2L);
    }

    @Test
    void a_directory_suite_leaves_its_claimed_tests_to_their_suites() throws Exception {
        assertThat(total(suites("e2e"))).as("CheckoutE2e's untagged test").isEqualTo(1L);
    }

    @Test
    void the_gate_runs_the_default_suite_and_integration_once_each() throws Exception {
        assertThat(total(TestSelection.of(List.of("test", "integration"), false, List.of(), List.of(), false, true)))
                .isEqualTo(4L);
    }

    @Test
    void a_tagged_class_named_under_the_default_suite_says_where_it_lives() throws Exception {
        Run run = run(TestSelection.DEFAULT.withClasses(List.of("CartTest")));
        assertThat(run.result().success()).isFalse();
        assertThat(run.result().errors()).singleElement().satisfies(d -> assertThat(d.message())
                .isEqualTo("no test classes matched --class CartTest: CartTest is in the integration suite"
                        + " (jk test -s integration -S CartTest)"));
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
