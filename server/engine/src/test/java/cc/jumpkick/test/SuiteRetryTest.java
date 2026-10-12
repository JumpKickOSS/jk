// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import cc.jumpkick.engine.plugin.WorkerContainment;
import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which test failures are one heap retry, and which of them re-run the whole suite. */
class SuiteRetryTest {

    @Test
    void a_suite_that_dies_after_reporting_tests_is_retried_as_a_whole() {
        TestSummary died = summary(new TestFailureInfo("g:app", "", "", "(test run)", "", "worker exited 3", ""));
        assertThat(SuiteRetry.retryable(died.failures().getFirst())).isTrue();
        assertThat(SuiteRetry.wholeSuite(died)).isTrue();
        assertThat(SuiteRetry.onlyHeap(died)).isTrue();
        assertThat(SuiteRetry.classes(died, List.of())).isEmpty();
        assertThat(SuiteRetry.killed(died.failures().getFirst())).isFalse();
    }

    @Test
    void a_named_class_is_the_item_and_an_assertion_is_not_a_heap_failure() {
        TestFailureInfo worker = new TestFailureInfo(
                "g:app", "", "com.acme.BigTest", "(worker 1)", "", "test worker exited 3 mid-run", "");
        TestFailureInfo assertion =
                new TestFailureInfo("g:app", "", "com.acme.OtherTest", "nope()", "AssertionError", "expected 1", "");
        assertThat(SuiteRetry.classes(summary(worker), List.of())).containsExactly("com.acme.BigTest");
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
    void the_classes_a_pool_left_queued_join_a_due_retry_and_no_other() {
        TestFailureInfo kill = new TestFailureInfo(
                "g:app", "", "com.acme.BigTest", "(worker 1)", "", "test worker killed for memory", "");
        TestFailureInfo quit = new TestFailureInfo(
                "g:app", "", "com.acme.QuitTest", "(worker 2)", "", "test worker exited 0 before it was told DONE", "");
        TestFailureInfo shortfall = requireNonNull(RunShortfall.row("g:app", false, true, 2));
        List<String> queued = List.of("com.acme.LateTest", "com.acme.LastTest");
        assertThat(SuiteRetry.classes(summary(kill, quit, shortfall), queued))
                .containsExactly("com.acme.BigTest", "com.acme.LateTest", "com.acme.LastTest");
        assertThat(SuiteRetry.classes(summary(quit, shortfall), queued)).isEmpty();
    }

    @Test
    void a_retry_that_ran_the_queued_classes_drops_the_shortfall_row() {
        TestFailureInfo kill = new TestFailureInfo(
                "g:app", "", "com.acme.BigTest", "(worker 1)", "", "test worker killed for memory", "");
        TestFailureInfo quit = new TestFailureInfo(
                "g:app", "", "com.acme.QuitTest", "(worker 2)", "", "test worker exited 0 before it was told DONE", "");
        TestFailureInfo shortfall = requireNonNull(RunShortfall.row("g:app", false, true, 1));
        TestSummary first = new TestSummary(4, 1, 3, 0, 1, List.of(kill, quit, shortfall));
        TestSummary second = new TestSummary(2, 2, 0, 0, 2, List.of());
        TestSummary merged = SuiteRetry.merge(
                first, List.of("com.acme.BigTest", "com.acme.LateTest"), new MarkdownTestReport.Tally(0, 0, 0), second);
        assertThat(merged.failures()).containsExactly(quit);
        assertThat(merged.total()).isEqualTo(4);
        assertThat(merged.succeeded()).isEqualTo(3);
        assertThat(merged.failed()).isEqualTo(1);
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
        assertThat(SuiteRetry.classes(summary(capped), List.of())).isEmpty();
    }

    @Test
    void a_retry_s_run_replaces_the_retried_classes_counts_rows_and_walls() {
        TestFailureInfo heap = new TestFailureInfo(
                "g:app", "", "com.acme.BigTest", "alloc()", "java.lang.OutOfMemoryError", "Java heap space", "");
        TestFailureInfo assertion =
                new TestFailureInfo("g:app", "", "com.acme.BigTest", "shape()", "AssertionError", "expected 1", "");
        TestFailureInfo other =
                new TestFailureInfo("g:app", "", "com.acme.OtherTest", "nope()", "AssertionError", "expected 2", "");
        // BigTest: two passes, a skip, the heap failure and an assertion; OtherTest: one pass, one failure.
        TestSummary first =
                new TestSummary(7, 3, 3, 1, 2, List.of(heap, assertion, other), Map.of("com.acme.OtherTest", 40L), 1);
        TestSummary second = new TestSummary(4, 3, 1, 0, 1, List.of(assertion), Map.of("com.acme.BigTest", 90L), 1);

        TestSummary merged =
                SuiteRetry.merge(first, List.of("com.acme.BigTest"), new MarkdownTestReport.Tally(2, 1, 1), second);

        assertThat(merged.total()).isEqualTo(6);
        assertThat(merged.succeeded()).isEqualTo(4);
        assertThat(merged.failed()).isEqualTo(2);
        assertThat(merged.skipped()).isZero();
        assertThat(merged.classes()).isEqualTo(2);
        assertThat(merged.failures()).containsExactly(other, assertion);
        assertThat(merged.classWallMs()).containsOnly(entry("com.acme.OtherTest", 40L), entry("com.acme.BigTest", 90L));
    }

    @Test
    void a_merge_after_a_suite_death_drops_the_blank_class_heap_row() {
        TestFailureInfo died = new TestFailureInfo("g:app", "", "", "(test run)", "", "worker exited 3", "");
        TestSummary first = new TestSummary(2, 1, 1, 0, 1, List.of(died));
        TestSummary second = new TestSummary(1, 1, 0, 0, 1, List.of());
        TestSummary merged =
                SuiteRetry.merge(first, List.of("com.acme.BigTest"), new MarkdownTestReport.Tally(1, 0, 1), second);
        assertThat(merged.failures()).isEmpty();
        assertThat(merged.total()).isEqualTo(1);
        assertThat(merged.succeeded()).isEqualTo(1);
        assertThat(merged.failed()).isZero();
        assertThat(merged.classes()).isEqualTo(1);
    }

    private static TestSummary summary(TestFailureInfo... failures) {
        return new TestSummary(failures.length, 0, failures.length, 0, List.of(failures));
    }
}
