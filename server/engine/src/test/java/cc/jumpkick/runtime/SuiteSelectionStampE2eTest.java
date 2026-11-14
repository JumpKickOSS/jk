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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The suites a run stamps are the suites it runs. A green marker stored while a suite was left out
 * must not replay once that suite would run: flipping a suite's {@code explicit} under {@code
 * --all}, or a request that names no tags under a profile whose {@code all-suites} is on (an MCP or
 * HTTP build, a BSP run), runs the failing test the suite claims.
 */
// Out of the unit tier: network resolve + real forked test JVMs.
@Tag("integration")
class SuiteSelectionStampE2eTest {

    @TempDir
    Path tmp;

    @Test
    void flipping_a_suites_explicit_flag_reruns_all_and_runs_the_suite_it_left_out() throws Exception {
        Path project = project(tmp, """
                [test.suites.live]
                tags = ["live"]
                explicit = true
                """);
        TestSelection all = TestSelection.of(List.of(), true, List.of(), List.of());
        Run first = run(project, all, null);
        assertThat(first.result().success())
                .as("-A leaves the explicit live suite out")
                .isTrue();
        assertThat(first.tests().total()).isEqualTo(1L);

        Files.writeString(project.resolve("jk.toml"), manifest("""
                [test.suites.live]
                tags = ["live"]
                """));
        Run second = run(project, all, null);
        assertThat(second.result().success())
                .as("-A now runs the live suite, whose test fails; a replayed green marker would pass")
                .isFalse();
        assertThat(second.tests().failed()).isEqualTo(1L);
    }

    @Test
    void a_profiles_all_suites_runs_every_suite_for_a_request_that_names_none() throws Exception {
        Path project = project(tmp, """
                [profiles.ci]
                all-suites = true

                [test.suites.live]
                tags = ["live"]
                """);
        Run ci = run(project, TestSelection.DEFAULT, "ci");
        assertThat(ci.result().success())
                .as("the ci profile runs every suite, the live suite's failing test with them")
                .isFalse();
        assertThat(ci.tests().failed()).isEqualTo(1L);
    }

    private record Run(BuildPlan plan, BuildPlanResult result) {
        TestSummary tests() {
            return plan.get(BuildPlanner.TEST_RUN).map(TestRun::result).orElseThrow();
        }
    }

    private Run run(Path project, TestSelection selection, @Nullable String profile) throws Exception {
        Session session = Session.defaults().withTestSelection(selection);
        BuildPlanner.Inputs in = TestInputs.of(project, tmp.resolve("cache"), session)
                .withProfileName(profile)
                .withEstimatedTestCount(1)
                .withTestOnly(true);
        BuildPlan plan = BuildPlanner.coreBuilder(in).build();
        return new Run(plan, SessionContext.where(session, plan::run));
    }

    private static Path project(Path tmp, String suites) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("jk.toml"), manifest(suites));
        Path main = Files.createDirectories(project.resolve("src/com/example"));
        Files.writeString(main.resolve("App.java"), "package com.example;\npublic final class App {}\n");
        Path tests = Files.createDirectories(project.resolve("test/src/com/example"));
        Files.writeString(tests.resolve("PlainTest.java"), testClass("PlainTest", "", ""));
        Files.writeString(
                tests.resolve("LiveTest.java"), testClass("LiveTest", "@Tag(\"live\")", "fail(\"live ran\");"));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, tmp.resolve("cache"), null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).as("lock").isTrue();
        return project;
    }

    private static String manifest(String suites) {
        return """
                group   = "com.example"
                name    = "app"
                version = "1.0.0"
                java    = 25

                %s
                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "6.1.3" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """.formatted(suites);
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
