// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.WorkerContainment;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which test failures are one heap retry, and which of them re-run the whole suite. */
class SuiteRetryTest {

    @Test
    void a_suite_that_dies_after_reporting_tests_is_retried_as_a_whole() {
        TestSummary died = summary(new TestFailureInfo("g:app", "", "", "(test run)", "", "worker exited 3", ""));
        assertThat(SuiteRetry.retryable(died.failures().getFirst())).isTrue();
        assertThat(SuiteRetry.wholeSuite(died)).isTrue();
        assertThat(SuiteRetry.onlyHeap(died)).isTrue();
        assertThat(SuiteRetry.classes(died)).isEmpty();
        assertThat(SuiteRetry.killed(died.failures().getFirst())).isFalse();
    }

    @Test
    void a_named_class_is_the_item_and_an_assertion_is_not_a_heap_failure() {
        TestFailureInfo worker = new TestFailureInfo(
                "g:app", "", "com.acme.BigTest", "(worker 1)", "", "test worker exited 3 mid-run", "");
        TestFailureInfo assertion =
                new TestFailureInfo("g:app", "", "com.acme.OtherTest", "nope()", "AssertionError", "expected 1", "");
        assertThat(SuiteRetry.classes(summary(worker))).containsExactly("com.acme.BigTest");
        assertThat(SuiteRetry.wholeSuite(summary(worker))).isFalse();
        assertThat(SuiteRetry.onlyHeap(summary(worker, assertion))).isFalse();
        assertThat(SuiteRetry.retryable(assertion)).isFalse();
    }

    @Test
    void a_memory_kill_is_retried_without_being_called_a_heap_exhaustion() {
        TestFailureInfo kill = new TestFailureInfo(
                "g:app", "", "com.acme.BigTest", "(worker 1)", "", "test worker killed for memory", "");
        assertThat(SuiteRetry.retryable(kill)).isTrue();
        assertThat(SuiteRetry.killed(kill)).isTrue();
    }

    @Test
    void a_kill_at_the_worker_cap_is_not_retried() {
        TestFailureInfo capped = new TestFailureInfo(
                "g:app",
                "",
                "com.acme.BigTest",
                "(worker 1)",
                "",
                "test worker " + WorkerContainment.capPhrase(10L << 30),
                "");
        assertThat(SuiteRetry.retryable(capped)).isFalse();
        assertThat(SuiteRetry.killed(capped)).isFalse();
        assertThat(SuiteRetry.classes(summary(capped))).isEmpty();
    }

    private static TestSummary summary(TestFailureInfo... failures) {
        return new TestSummary(failures.length, 0, failures.length, 0, List.of(failures));
    }
}
