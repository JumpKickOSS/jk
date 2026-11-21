// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.run.TestClassResult;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The workspace test wedge names how many modules' suites were served from the action cache, and
 * says {@code Build up to date} when every suite was a replay.
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
        String t = TestAnsi.strip(TestVerb.workspaceTestSuccessTail(modules(3), 3, 0, 3, 0, 0, 0, 1200));
        assertThat(t).startsWith("Tests passed for 3 modules");
        assertThat(t).doesNotContain("served");
    }

    @Test
    void a_partly_served_run_names_the_count() {
        String t = TestAnsi.strip(TestVerb.workspaceTestSuccessTail(modules(49), 49, 47, 49, 0, 0, 0, 4600));
        assertThat(t).contains("49 modules, 47 served from cache");
    }

    @Test
    void a_wholly_served_run_says_so() {
        String t = TestAnsi.strip(TestVerb.workspaceTestSuccessTail(modules(4), 4, 4, 4, 0, 0, 0, 300));
        assertThat(t).startsWith("Tests passed for 4 modules. Build up to date (all served from cache) ");
    }

    @Test
    void one_module_served() {
        String t = TestAnsi.strip(TestVerb.workspaceTestSuccessTail(modules(1), 1, 1, 1, 0, 0, 0, 30));
        assertThat(t).startsWith("Tests passed. Build up to date (served from cache) ");
    }

    /**
     * `jk test -p <tier>` over modules that carry no such tier: every module finished, none
     * had a suite. Green, but not a passing suite — the line must not claim one.
     */
    @Test
    void a_run_where_no_module_had_a_suite_says_there_was_nothing_to_run() {
        assertThat(TestAnsi.strip(TestVerb.workspaceTestSuccessTail(modules(5), 5, 0, 0, 0, 0, 0, 900)))
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

    /** `jk test -m app -X` over app and three prerequisites: one module tested, the three named apart. */
    @Test
    void an_exclusive_run_counts_the_prerequisites_apart() {
        assertThat(TestAnsi.strip(TestVerb.workspaceTestSuccessTail(modules(4), 4, 0, 1, 3, 0, 0, 900)))
                .startsWith("Tests passed; 3 prerequisites not tested (--exclusive) ");
        assertThat(TestAnsi.strip(TestVerb.workspaceTestSuccessTail(modules(5), 5, 0, 2, 3, 0, 0, 900)))
                .startsWith("Tests passed for 2 modules; 3 prerequisites not tested (--exclusive) ");
        assertThat(TestAnsi.strip(TestVerb.workspaceTestSuccessTail(modules(2), 2, 0, 0, 1, 0, 0, 900)))
                .isEqualTo("No tests to run; 1 prerequisite not tested (--exclusive)");
    }

    @Test
    void the_tally_counts_an_exclusive_prerequisite_apart_from_suites_and_replays() {
        ServedTally tally = new ServedTally();
        tally.label(TaskNames.RUN_TESTS, TaskNames.TESTS_NOT_SELECTED);
        tally.stepFinish(TaskNames.RUN_TESTS, null, TaskStatus.SKIPPED, Duration.ZERO, Duration.ZERO);
        assertThat(tally.notSelected()).isEqualTo(1);
        assertThat(tally.withSuite()).isZero();
        assertThat(tally.served()).isZero();
    }

    /** A green run with a disabled test says so: the pass is not the whole story. */
    @Test
    void a_run_with_skipped_tests_says_how_many() {
        assertThat(TestAnsi.strip(TestVerb.workspaceTestSuccessTail(modules(1), 1, 0, 1, 0, 2, 1, 900)))
                .startsWith("Tests passed; 1 test skipped ");
        assertThat(TestAnsi.strip(TestVerb.workspaceTestSuccessTail(modules(3), 3, 1, 3, 0, 40, 3, 900)))
                .startsWith("Tests passed for 3 modules, 1 served from cache; 3 tests skipped ");
        assertThat(TestAnsi.strip(TestVerb.workspaceTestSuccessTail(modules(4), 4, 0, 1, 3, 5, 2, 900)))
                .startsWith("Tests passed; 2 tests skipped; 3 prerequisites not tested (--exclusive) ");
    }

    /** `jk test --class` on a class whose only test is disabled: nothing passed, and the line must not say so. */
    @Test
    void a_run_whose_every_test_was_skipped_does_not_say_they_passed() {
        String t = TestAnsi.strip(TestVerb.workspaceTestSuccessTail(modules(1), 1, 0, 1, 0, 1, 1, 900));
        assertThat(t).startsWith("No test ran; 1 test skipped ");
        assertThat(t).doesNotContain("passed");
    }

    @Test
    void the_tally_adds_up_the_tests_each_class_ran_and_skipped() {
        ServedTally tally = new ServedTally();
        tally.testClass(TaskNames.RUN_TESTS, new TestClassResult("g:a", "a.ATest", 3, 0, 1, 10, "", List.of()));
        tally.testClass(TaskNames.RUN_TESTS, new TestClassResult("g:a", "a.BTest", 2, 1, 2, 10, "", List.of()));
        assertThat(tally.ranTests()).isEqualTo(5);
        assertThat(tally.skippedTests()).isEqualTo(3);
    }
}
