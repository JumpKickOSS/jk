// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.run.TestFailureInfo;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A cancelled run that left classes unrun reports that as a failure, so run-tests cannot stamp
 * the suite green off the classes that happened to finish first.
 */
class CancelledShortfallTest {

    @Test
    void an_uncancelled_run_reports_nothing_extra() {
        assertThat(CancelledShortfall.of(false, 0, 0)).isNull();
        assertThat(CancelledShortfall.of(false, 137, 12)).isNull(); // worker deaths are reported per worker
    }

    @Test
    void a_cancel_that_landed_after_the_last_class_leaves_the_summary_alone() {
        assertThat(CancelledShortfall.of(true, 0, 0)).isNull();
    }

    @Test
    void undispatched_classes_are_named_in_the_failure() {
        assertThat(CancelledShortfall.of(true, 0, 1)).isEqualTo("test run cancelled: 1 class never ran");
        assertThat(CancelledShortfall.of(true, 0, 12)).isEqualTo("test run cancelled: 12 classes never ran");
    }

    @Test
    void a_worker_stopped_mid_class_counts_even_when_the_queue_was_drained() {
        assertThat(CancelledShortfall.of(true, 137, 0)).isEqualTo("test run cancelled: a worker was stopped mid-class");
        assertThat(CancelledShortfall.of(true, 137, 3))
                .isEqualTo("test run cancelled: 3 classes never ran; a worker was stopped mid-class");
    }

    @Test
    void the_row_is_recognised_as_a_shortfall_and_a_crash_row_is_not() {
        TestFailureInfo row = requireNonNull(CancelledShortfall.row("cli", true, 0, 2));
        assertThat(row.method()).isEqualTo("(test run)");
        assertThat(CancelledShortfall.isRow(row)).isTrue();
        assertThat(CancelledShortfall.row("cli", false, 0, 2)).isNull();

        TestFailureInfo crash = new TestFailureInfo("cli", "", "", "(test run)", "", "worker 1 exited 137", "", 1);
        TestFailureInfo red = new TestFailureInfo("cli", "", "com.acme.FooTest", "fails()", "", "boom", "", 0);
        assertThat(CancelledShortfall.isRow(crash)).isFalse();
        assertThat(CancelledShortfall.rows(List.of(row, crash, red, row))).isEqualTo(2);
    }
}
