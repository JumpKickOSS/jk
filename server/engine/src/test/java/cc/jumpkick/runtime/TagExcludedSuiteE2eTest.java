// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.testing.TestCaches;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A profile whose tag filter drops every test in a module is a tier the module has no tests in:
 * the step reports {@code 0 tests (all excluded by profile <name>)} as a plain line, passes, and
 * raises no discovery warning.
 */
// Out of the unit tier: network resolve of JUnit + a forked test JVM.
@Tag("integration")
class TagExcludedSuiteE2eTest {

    @Test
    void a_profile_that_excludes_every_test_reports_one_plain_line_and_no_warning(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("tiered");
        Files.createDirectories(project);
        Files.writeString(project.resolve("jk.toml"), """
                name    = "tiered"
                group   = "com.example"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }

                [profiles.nightly]
                include-tags = ["nightly"]

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path test = Files.createDirectories(project.resolve("test/src/com/example"));
        Files.writeString(test.resolve("TaggedTest.java"), """
                package com.example;

                import org.junit.jupiter.api.Tag;
                import org.junit.jupiter.api.Test;

                @Tag("integration")
                class TaggedTest {
                    @Test
                    void runs() {}
                }
                """);
        Files.writeString(test.resolve("PlainTest.java"), """
                package com.example;

                import org.junit.jupiter.api.Test;

                class PlainTest {
                    @Test
                    void runs() {}
                }
                """);

        List<String> outputs = new CopyOnWriteArrayList<>();
        BuildPlan plan = lockAndPlan(project);
        plan.addListener(new BuildPlanListener() {
            @Override
            public void output(String step, String line) {
                outputs.add(step + ": " + line);
            }
        });
        BuildPlanResult result = plan.run();

        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(result.warnings())
                .as("a tag-emptied tier is the expected outcome, not a discovery warning")
                .noneMatch(d -> "no-tests-discovered".equals(d.code()))
                .noneMatch(d -> d.message() != null && d.message().contains("discovery found 0 tests"));
        assertThat(outputs).contains("run-tests: 0 tests (all excluded by profile nightly)");
        TestSummary tests = plan.get(BuildPlanner.TEST_RESULT).orElseThrow();
        assertThat(tests.total()).isZero();
        assertThat(tests.failed()).isZero();
    }

    /** The plan of {@code jk test --profile nightly}: the profile's tags resolved onto the session. */
    private static BuildPlan lockAndPlan(Path project) throws Exception {
        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        Path cache = TestCaches.dir("tag-excluded-suite-cache");
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).isTrue();
        TestSelection nightly = TestSelection.of(List.of(), false, List.of("nightly"), List.of(), true);
        BuildPlanner.Inputs in = new BuildPlanner.Inputs(
                project,
                cache,
                project.resolve("jk.toml"),
                project.resolve("jk-lock.toml"),
                project,
                1,
                1,
                "nightly",
                null,
                false,
                false,
                false,
                false,
                Set.of(),
                SessionContext.current().withTestSelection(nightly));
        return BuildPlanner.fullPlan(in);
    }
}
