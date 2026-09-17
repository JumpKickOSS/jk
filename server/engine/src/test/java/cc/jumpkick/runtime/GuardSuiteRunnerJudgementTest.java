// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A run is judged from the report the fork left, not from the launcher's count of its events: the
 * report is the fork's own word on which guards ran, and under load the launcher counts fewer.
 */
class GuardSuiteRunnerJudgementTest {

    private static TestSummary summary(long total, long failed) {
        List<TestFailureInfo> failures = failed == 0
                ? List.of()
                : List.of(new TestFailureInfo("m", "jupiter", "fx.Other", "plain", "AssertionError", "boom", ""));
        return new TestSummary(total, total - failed, failed, 0, failures);
    }

    @Test
    void a_report_holding_every_guard_is_a_run_whatever_the_launcher_counted() {
        assertThat(GuardSuiteRunner.problems(summary(0, 0), 20, List.of()))
                .as("the launcher counted no event of a suite whose report holds twenty guards")
                .isEmpty();
        assertThat(GuardSuiteRunner.problems(summary(7, 0), 20, List.of()))
                .as("a short count is the launcher's, not the suite's")
                .isEmpty();
    }

    @Test
    void no_report_and_no_event_is_a_suite_that_ran_nothing() {
        assertThat(GuardSuiteRunner.problems(summary(0, 0), 0, List.of()))
                .singleElement()
                .asString()
                .contains("ran no @Guard method");
    }

    @Test
    void a_guard_the_launcher_saw_but_the_report_lacks_is_named_with_what_the_fork_said() {
        assertThat(GuardSuiteRunner.problems(summary(20, 0), 19, List.of("report append failed")))
                .singleElement()
                .asString()
                .contains("ran 20 guard(s) but reported 19")
                .contains("report append failed");
    }

    @Test
    void a_test_failing_outside_any_guard_fails_the_run_even_with_a_whole_report() {
        assertThat(GuardSuiteRunner.problems(summary(20, 1), 20, List.of()))
                .singleElement()
                .asString()
                .contains("failed 1 test(s) outside any @Guard");
    }
}
