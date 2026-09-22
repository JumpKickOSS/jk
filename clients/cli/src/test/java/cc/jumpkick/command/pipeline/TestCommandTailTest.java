// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The workspace test wedge names how many modules' suites were served from the action cache, and a
 * plain project's {@code Passed N tests} says when the suite was a replay and not a run.
 */
class TestCommandTailTest {

    private static WorkspaceResult modules(int n) {
        List<ModuleOutcome> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new ModuleOutcome("g:m" + i, Path.of("/ws/m" + i), true, 0, 10, true));
        }
        return new WorkspaceResult(true, 0, out, List.of());
    }

    @Test
    void a_run_that_served_nothing_reads_as_before() {
        String t = TestAnsi.strip(TestCommand.workspaceTestSuccessTail(modules(3), 3, 0, 3, 1200));
        assertThat(t).startsWith("Tests passed for 3 modules");
        assertThat(t).doesNotContain("served");
    }

    @Test
    void a_partly_served_run_names_the_count() {
        String t = TestAnsi.strip(TestCommand.workspaceTestSuccessTail(modules(49), 49, 47, 49, 4600));
        assertThat(t).contains("49 modules, 47 served from cache");
    }

    @Test
    void a_wholly_served_run_says_so() {
        String t = TestAnsi.strip(TestCommand.workspaceTestSuccessTail(modules(4), 4, 4, 4, 300));
        assertThat(t).contains("4 modules, all served from cache");
    }

    @Test
    void one_module_served() {
        String t = TestAnsi.strip(TestCommand.workspaceTestSuccessTail(modules(1), 1, 1, 1, 30));
        assertThat(t).startsWith("Tests passed, served from cache");
    }

    @Test
    void a_plain_projects_replayed_suite_says_it_was_served_from_cache() {
        TestSummary twelve = new TestSummary(12, 12, 0, 0, List.of());
        BuildPlanResult result =
                new BuildPlanResult("test", true, Duration.ofMillis(30), List.of(), List.of(), List.of(), false);
        assertThat(TestAnsi.strip(TestCommand.testSummary(twelve, result, true)))
                .isEqualTo("Passed 12 tests (served from cache)");
        assertThat(TestAnsi.strip(TestCommand.testSummary(twelve, result, false)))
                .isEqualTo("Passed 12 tests");
        assertThat(TestAnsi.strip(TestCommand.testSummary(new TestSummary(0, 0, 0, 0, List.of()), result, true)))
                .isEqualTo("No tests");
    }

    /**
     * `jk test --profile <tier>` over modules that carry no such tier: every module finished, none
     * had a suite. Green, but not a passing suite — the line must not claim one.
     */
    @Test
    void a_run_where_no_module_had_a_suite_says_there_was_nothing_to_run() {
        assertThat(TestAnsi.strip(TestCommand.workspaceTestSuccessTail(modules(5), 5, 0, 0, 900)))
                .isEqualTo("No tests to run");
    }

    @Test
    void the_tally_separates_the_suites_that_ran_from_the_ones_served() {
        ServedTally replayed = new ServedTally();
        replayed.label(TaskNames.RUN_TESTS, TaskNames.TESTS_UP_TO_DATE);
        replayed.stepFinish(TaskNames.RUN_TESTS, null, TaskStatus.SKIPPED, Duration.ZERO, Duration.ZERO);
        assertThat(replayed.withSuite()).as("a replayed suite is a suite").isEqualTo(1);

        ServedTally ran = new ServedTally();
        ran.stepFinish(TaskNames.RUN_TESTS, null, TaskStatus.SUCCESS, Duration.ZERO, Duration.ZERO);
        assertThat(ran.withSuite()).isEqualTo(1);
        assertThat(ran.served()).isZero();

        ServedTally none = new ServedTally();
        none.label(TaskNames.RUN_TESTS, "no tests");
        none.stepFinish(TaskNames.RUN_TESTS, null, TaskStatus.SKIPPED, Duration.ZERO, Duration.ZERO);
        assertThat(none.withSuite())
                .as("a skip that replayed nothing ran nothing")
                .isZero();

        ServedTally absent = new ServedTally();
        absent.stepFinish(TaskNames.COMPILE_MAIN, null, TaskStatus.SUCCESS, Duration.ZERO, Duration.ZERO);
        assertThat(absent.withSuite())
                .as("a module with no run-tests step at all")
                .isZero();
    }

    @Test
    void the_tally_counts_a_run_tests_step_skipped_under_the_up_to_date_label_only() {
        ServedTally served = new ServedTally();
        served.label(TaskNames.RUN_TESTS, TaskNames.TESTS_UP_TO_DATE);
        served.stepFinish(TaskNames.RUN_TESTS, null, TaskStatus.SKIPPED, Duration.ZERO, Duration.ZERO);
        assertThat(served.served()).isEqualTo(1);

        ServedTally noTests = new ServedTally();
        noTests.label(TaskNames.RUN_TESTS, "no tests");
        noTests.stepFinish(TaskNames.RUN_TESTS, null, TaskStatus.SKIPPED, Duration.ZERO, Duration.ZERO);
        assertThat(noTests.served()).isZero();

        ServedTally ran = new ServedTally();
        ran.label(TaskNames.RUN_TESTS, TaskNames.TESTS_UP_TO_DATE);
        ran.stepFinish(TaskNames.RUN_TESTS, null, TaskStatus.SUCCESS, Duration.ZERO, Duration.ZERO);
        assertThat(ran.served()).isZero();
    }
}
