// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TestFailureInfo;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A run that left classes unrun reports that as a failure, cancelled or not, so run-tests cannot
 * stamp the suite green off the classes that happened to finish first.
 */
class RunShortfallTest {

    @Test
    void an_uncancelled_run_whose_workers_drained_the_queue_reports_nothing_extra() {
        assertThat(RunShortfall.of(false, false, 0)).isNull();
        assertThat(RunShortfall.of(false, true, 0)).isNull(); // worker deaths are reported per worker
    }

    @Test
    void an_uncancelled_run_with_classes_still_queued_names_them() {
        assertThat(RunShortfall.of(false, true, 1)).isEqualTo("test run incomplete: 1 class never ran");
        assertThat(RunShortfall.of(false, false, 12)).isEqualTo("test run incomplete: 12 classes never ran");
    }

    @Test
    void a_cancel_that_landed_after_the_last_class_leaves_the_summary_alone() {
        assertThat(RunShortfall.of(true, false, 0)).isNull();
    }

    @Test
    void undispatched_classes_are_named_in_the_cancelled_failure() {
        assertThat(RunShortfall.of(true, false, 1)).isEqualTo("test run cancelled: 1 class never ran");
        assertThat(RunShortfall.of(true, false, 12)).isEqualTo("test run cancelled: 12 classes never ran");
    }

    @Test
    void a_worker_stopped_mid_class_counts_even_when_the_queue_was_drained() {
        assertThat(RunShortfall.of(true, true, 0)).isEqualTo("test run cancelled: a worker was stopped mid-class");
        assertThat(RunShortfall.of(true, true, 3))
                .isEqualTo("test run cancelled: 3 classes never ran; a worker was stopped mid-class");
    }

    @Test
    void the_row_is_recognised_as_a_shortfall_and_a_crash_row_is_not() {
        TestFailureInfo cancelled = requireNonNull(RunShortfall.row("cli", true, false, 2));
        TestFailureInfo incomplete = requireNonNull(RunShortfall.row("cli", false, true, 2));
        assertThat(cancelled.method()).isEqualTo("(test run)");
        assertThat(incomplete.method()).isEqualTo("(test run)");
        assertThat(RunShortfall.isRow(cancelled)).isTrue();
        assertThat(RunShortfall.isRow(incomplete)).isTrue();
        assertThat(RunShortfall.row("cli", false, true, 0)).isNull();

        TestFailureInfo crash = new TestFailureInfo("cli", "", "", "(test run)", "", "worker 1 exited 137", "", 1);
        TestFailureInfo quit = new TestFailureInfo(
                "cli", "", "", "(test run)", "", "worker exited 0 before finishing its plan (2 tests reported)", "", 0);
        TestFailureInfo red = new TestFailureInfo("cli", "", "com.acme.FooTest", "fails()", "", "boom", "", 0);
        assertThat(RunShortfall.isRow(crash)).isFalse();
        assertThat(RunShortfall.isRow(quit)).isFalse();
        assertThat(RunShortfall.rows(List.of(cancelled, crash, red, incomplete, quit)))
                .isEqualTo(2);
    }
}
