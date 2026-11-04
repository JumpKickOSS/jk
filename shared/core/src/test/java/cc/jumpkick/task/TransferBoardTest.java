// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.FakeClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TransferBoardTest {

    private final FakeClock clock = new FakeClock();
    private final TransferBoard board = new TransferBoard(clock);

    @Test
    void a_board_nothing_touched_is_the_empty_snapshot() {
        assertThat(board.snapshot()).isEqualTo(TransferBoard.Snapshot.EMPTY);
        board.open("g:a:1").close(); // opened, never started: a store hit
        assertThat(board.snapshot()).isEqualTo(TransferBoard.Snapshot.EMPTY);
    }

    @Test
    void a_transfer_is_shown_only_once_it_outlives_the_dwell() {
        try (var quick = board.open("g:quick:1")) {
            quick.started(1_000);
            quick.advanced(1_000);
            assertThat(board.snapshot().rows()).as("inside the dwell").isEmpty();
            assertThat(board.snapshot().inflight()).isEqualTo(1);
            quick.finished();
        }
        clock.advance(Duration.ofMillis(300));

        TransferBoard.Snapshot after = board.snapshot();
        assertThat(after.rows()).as("it landed before it was worth a row").isEmpty();
        assertThat(after.inflight()).isZero();
        assertThat(after.files()).isEqualTo(1);
        assertThat(after.bytes()).isEqualTo(1_000);
    }

    @Test
    void rows_keep_the_order_their_transfers_began_and_carry_their_bytes() {
        var first = board.open("g:first:1");
        first.started(4_000);
        clock.advance(Duration.ofMillis(10));
        var second = board.open("g:second:1");
        second.started(-1);
        clock.advance(Duration.ofMillis(300));
        second.advanced(2_048);
        first.advanced(1_000);

        assertThat(board.snapshot().rows())
                .containsExactly(
                        new TransferBoard.Row("g:first:1", 1_000, 4_000),
                        new TransferBoard.Row("g:second:1", 2_048, -1));
        assertThat(board.snapshot().bytes()).isEqualTo(3_048);
    }

    @Test
    void rows_are_capped_and_inflight_counts_the_rest() {
        for (int i = 0; i < TransferBoard.MAX_ROWS + 4; i++) {
            board.open("g:a" + i + ":1").started(100);
            clock.advance(Duration.ofMillis(1));
        }
        clock.advance(Duration.ofMillis(300));

        TransferBoard.Snapshot snap = board.snapshot();
        assertThat(snap.rows()).hasSize(TransferBoard.MAX_ROWS);
        assertThat(snap.rows().getFirst().label()).isEqualTo("g:a0:1");
        assertThat(snap.inflight()).isEqualTo(TransferBoard.MAX_ROWS + 4);
    }

    /** A body that has landed leaves the rows while its fetch verifies, so moving transfers show. */
    @Test
    void a_finished_body_leaves_the_rows_but_keeps_its_bytes() {
        var done = board.open("g:done:1");
        done.started(100);
        done.advanced(100);
        done.finished();
        var moving = board.open("g:moving:1");
        moving.started(100);
        moving.advanced(40);
        clock.advance(Duration.ofMillis(300));

        TransferBoard.Snapshot snap = board.snapshot();
        assertThat(snap.rows()).containsExactly(new TransferBoard.Row("g:moving:1", 40, 100));
        assertThat(snap.inflight()).isEqualTo(1);
        assertThat(snap.files()).isEqualTo(1);
        assertThat(snap.bytes()).isEqualTo(140);
    }

    /** A fetch that fails mid-body and retries elsewhere opens a fresh transfer; the dead one goes. */
    @Test
    void a_closed_unfinished_transfer_leaves_without_counting_as_a_file() {
        var failed = board.open("g:a:1");
        failed.started(100);
        failed.advanced(60);
        failed.close();

        TransferBoard.Snapshot snap = board.snapshot();
        assertThat(snap.inflight()).isZero();
        assertThat(snap.files()).isZero();
        assertThat(snap.bytes()).as("bytes that crossed the wire still count").isEqualTo(60);
    }

    @Test
    void open_plans_count_their_settled_files_and_a_closed_plan_leaves() {
        try (var lock = board.plan(3)) {
            lock.settled();
            lock.settled();
            try (var sync = board.plan(2)) {
                sync.settled();
                assertThat(board.snapshot().settled()).isEqualTo(3);
                assertThat(board.snapshot().planned()).isEqualTo(5);
            }
            assertThat(board.snapshot().settled()).isEqualTo(2);
            assertThat(board.snapshot().planned()).isEqualTo(3);
        }
        assertThat(board.snapshot()).isEqualTo(TransferBoard.Snapshot.EMPTY);
    }

    @Test
    void a_plan_never_reports_more_settled_than_planned() {
        try (var plan = board.plan(1)) {
            plan.settled();
            plan.settled();
            assertThat(board.snapshot().settled()).isEqualTo(1);
        }
    }
}
