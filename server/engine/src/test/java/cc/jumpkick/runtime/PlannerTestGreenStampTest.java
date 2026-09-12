// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The green run-tests marker is what lets every later build skip the suite, so it is stored only for
 * evidence: a module with test sources whose run executed nothing has none, and a marker for it
 * would replay "tests up-to-date" for tests that never ran.
 */
class PlannerTestGreenStampTest {

    @Test
    void a_run_that_executed_no_test_in_a_module_with_test_sources_earns_no_green_stamp() {
        assertThat(PlannerTest.greenStampAllowed(new TestSummary(0, 0, 0, 0, List.of()), true))
                .isFalse();
    }

    @Test
    void a_module_without_test_sources_may_stamp_its_empty_run() {
        assertThat(PlannerTest.greenStampAllowed(new TestSummary(0, 0, 0, 0, List.of()), false))
                .isTrue();
    }

    @Test
    void a_passing_run_with_tests_is_stamped_and_a_failing_one_is_not() {
        assertThat(PlannerTest.greenStampAllowed(new TestSummary(3, 2, 0, 1, List.of()), true))
                .isTrue();
        var failure = new TestFailureInfo("ex:m", "", "C", "m()", "AssertionError", "nope", "");
        assertThat(PlannerTest.greenStampAllowed(new TestSummary(1, 0, 1, 0, List.of(failure)), true))
                .isFalse();
    }
}
