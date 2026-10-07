// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.guard.eval.Freezer;
import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.base.TestStoreSeed;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A rule over the test classes keeps one population whatever runs it: a build compiles the tests
 * and records it, a {@code jk guard}-shaped run (tests skipped, guard asked for) leaves it alone,
 * and a freeze measures the test classes the build left.
 */
@Tag("integration")
class GuardTestClassPopulationTest {

    private static final TestSelection GUARD = TestSelection.of(List.of(), false, List.of(), List.of(), false, true);

    @Test
    void build_guard_and_freeze_agree_on_a_test_class_rules_population(@TempDir Path tmp) throws Exception {
        Path project = scaffold(tmp);
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Path baselineFile = GuardsPresence.baselineFile(project);

        BuildPlanResult first = build(project, cache, false, TestSelection.DEFAULT);
        assertThat(first.success())
                .as("the unmarked test class is a fresh site")
                .isFalse();

        // The freeze reads the test classes the build compiled, so it sees the site the build saw.
        Freezer.Result freeze = Freezer.freeze(project, "tests-deprecated", "accepted", false, false);
        assertThat(freeze.error()).isNull();
        assertThat(freeze.accepted()).isEqualTo(1);
        String recorded = Files.readString(baselineFile);
        assertThat(recorded).contains("tests-deprecated");

        BuildPlanResult build = build(project, cache, false, TestSelection.DEFAULT);
        assertThat(build.success()).as("errors: " + build.errors()).isTrue();
        assertThat(Files.readString(baselineFile))
                .as("the build measures what the freeze recorded")
                .isEqualTo(recorded);

        BuildPlanResult guard = build(project, cache, true, GUARD);
        assertThat(guard.success()).as("errors: " + guard.errors()).isTrue();
        assertThat(Files.readString(baselineFile))
                .as("a run that compiles no tests leaves the population the build recorded")
                .isEqualTo(recorded);
    }

    private static Path scaffold(Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        Files.writeString(project.resolve("jk.toml"), """
                name    = "proj"
                group   = "com.example"
                version = "1.0.0"
                jdk     = 25
                java    = 25

                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                """);
        Path main = Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(main.resolve("App.java"), "package demo;\n\npublic final class App {}\n");
        Path test = Files.createDirectories(project.resolve("src/test/java/demo"));
        Files.writeString(test.resolve("AppTest.java"), """
                package demo;

                import org.junit.jupiter.api.Test;

                class AppTest {
                    @Test
                    void runs() {}
                }
                """);
        Files.writeString(project.resolve(GuardsPresence.RULES_FILE), """
                [guards.tests-deprecated]
                kind    = "annotate"
                require = "java.lang.Deprecated"
                on      = "test-class"
                baseline = true
                instead = "mark it"
                why     = "a test rule"
                """);
        return project;
    }

    private static BuildPlanResult build(Path project, Path cache, boolean skipTests, TestSelection selection)
            throws Exception {
        TestStoreSeed.complete(JkDirs.store());
        var build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().errors()).isEmpty();
        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                project,
                cache,
                project.resolve("jk.toml"),
                project.resolve("jk-lock.toml"),
                project,
                1,
                0,
                null,
                null,
                skipTests,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current().withTestSelection(selection));
        return BuildPlanner.fullPlan(in).run();
    }
}
