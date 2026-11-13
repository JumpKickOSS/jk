// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestFailureInfo;
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
 * A suite's own settings reach its test JVMs and no other suite's: {@code integration}'s env and
 * system property are seen by its tests and not by the default suite's run beside it, {@code
 * hang}'s timeout fails a method that ignores interrupts, and the explicit suites stay out of
 * {@code --all} until named.
 */
// Out of the unit tier: network resolve + real forked test JVMs.
@Tag("integration")
class SuiteSettingsE2eTest {

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
                tags = ["integration"]
                env = { SUITE_MARK = "integration" }
                system-properties = { "suite.mark" = "integration" }

                [test.suites.hang]
                tags = ["hang"]
                timeout = "2s"
                explicit = true

                [test.suites.live]
                tags = ["live"]
                explicit = true

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(project.resolve("src/com/example"));
        Files.writeString(project.resolve("src/com/example/App.java"), """
                package com.example;
                public final class App {}
                """);
        Path tests = Files.createDirectories(project.resolve("test/src/com/example"));
        Files.writeString(tests.resolve("PlainTest.java"), testClass("PlainTest", "", """
                assertNull(System.getenv("SUITE_MARK"));
                assertNull(System.getProperty("suite.mark"));
                """));
        Files.writeString(tests.resolve("CartTest.java"), testClass("CartTest", "@Tag(\"integration\")", """
                assertEquals("integration", System.getenv("SUITE_MARK"));
                assertEquals("integration", System.getProperty("suite.mark"));
                """));
        Files.writeString(tests.resolve("HangTest.java"), testClass("HangTest", "@Tag(\"hang\")", """
                while (true) {
                    try { Thread.sleep(100); } catch (InterruptedException ignored) { }
                }
                """));
        Files.writeString(tests.resolve("LiveTest.java"), testClass("LiveTest", "@Tag(\"live\")", ""));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, tmp.resolve("cache"), null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();
    }

    @Test
    void a_suites_env_and_properties_reach_its_tests_and_not_the_default_suites() throws Exception {
        Run run = run(TestSelection.of(List.of("test", "integration"), false, List.of(), List.of(), false, true));
        assertThat(run.result().success()).as("%s", run.tests().failures()).isTrue();
        assertThat(run.tests().total())
                .as("PlainTest and CartTest, each in its own JVM")
                .isEqualTo(2L);
    }

    @Test
    void a_suites_timeout_fails_a_method_that_ignores_interrupts() throws Exception {
        Run run = run(suites("hang"));
        assertThat(run.result().success()).isFalse();
        assertThat(run.tests().failures())
                .singleElement()
                .extracting(TestFailureInfo::message)
                .asString()
                .contains("timed out after 2000 milliseconds");
    }

    @Test
    void all_leaves_the_explicit_suites_out_and_naming_one_runs_it() throws Exception {
        Run all = run(TestSelection.of(List.of(), true, List.of(), List.of()));
        assertThat(all.result().success()).as("%s", all.tests().failures()).isTrue();
        assertThat(all.tests().total())
                .as("PlainTest and CartTest; no hang, no live")
                .isEqualTo(2L);

        Run live = run(suites("live"));
        assertThat(live.result().success()).isTrue();
        assertThat(live.tests().total()).isEqualTo(1L);
    }

    private static TestSelection suites(String... names) {
        return TestSelection.of(List.of(names), false, List.of(), List.of());
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

    private static String testClass(String name, String classTags, String body) {
        return """
                package com.example;
                import org.junit.jupiter.api.Tag;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                %s
                class %s {
                    @Test void check() {
                %s    }
                }
                """.formatted(classTags, name, body.indent(8));
    }
}
