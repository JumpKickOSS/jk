// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.journal;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TestFailureInfo;
import cc.jumpkick.run.TestSummary;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The record's test counts are counts of tests. A fail-fast workspace stops the sibling runs
 * still in flight, and each of those reports one shortfall row so its step fails and stamps
 * nothing green; that row is not a test and the {@code Tests:} line does not count it.
 */
class BuildAccumulatorTestsFoldTest {

    private static final TestFailureInfo RED = new TestFailureInfo(
            "api", "junit-jupiter", "com.acme.FooTest", "fails()", "java.lang.AssertionError", "boom", "", 0);
    private static final TestFailureInfo STOPPED =
            new TestFailureInfo("cli", "", "", "(test run)", "", "test run cancelled: 4 classes never ran", "", 0);

    @Test
    void a_stopped_sibling_run_leaves_the_failed_and_total_counts() {
        BuildAccumulator a = new BuildAccumulator("build", "/ws", "g:ws", "cli");
        a.addTests(new TestSummary(3, 2, 1, 0, List.of(RED)));
        a.addTests(new TestSummary(1, 0, 1, 0, List.of(STOPPED)));
        a.addTests(new TestSummary(1, 0, 1, 0, List.of(STOPPED)));

        BuildRecord r = a.toRecord(2_000, false, 1_000, "9.9", null);
        BuildRecord.Tests tests = requireNonNull(r.tests());
        assertThat(tests.total()).isEqualTo(3);
        assertThat(tests.failed()).isEqualTo(1);
        assertThat(tests.succeeded()).isEqualTo(2);
        assertThat(JkResultsMarkdown.render(r)).contains("Tests: **1 failed**, 2 passed (3 total)");
    }

    @Test
    void a_run_that_was_only_a_shortfall_records_no_tests_line() {
        BuildAccumulator a = new BuildAccumulator("build", "/ws", "g:ws", "cli");
        a.addTests(new TestSummary(1, 0, 1, 0, List.of(STOPPED)));

        BuildRecord r = a.toRecord(2_000, false, 1_000, "9.9", null);
        assertThat(requireNonNull(r.tests()).total()).isZero();
        assertThat(JkResultsMarkdown.render(r)).doesNotContain("Tests:");
    }

    @Test
    void a_worker_crash_row_is_a_failure_and_stays_counted() {
        TestFailureInfo crash = new TestFailureInfo(
                "api", "", "", "(test run)", "", "worker 2 exited 137 while running com.acme.BarTest", "", 2);
        BuildAccumulator a = new BuildAccumulator("build", "/ws", "g:ws", "cli");
        a.addTests(new TestSummary(2, 1, 1, 0, List.of(crash)));

        assertThat(requireNonNull(a.toRecord(2_000, false, 1_000, "9.9", null).tests())
                        .failed())
                .isEqualTo(1);
    }
}
