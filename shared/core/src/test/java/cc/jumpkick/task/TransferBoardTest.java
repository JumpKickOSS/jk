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

    /** One body of {@code n} bytes, landed: what makes a declared set a download. */
    private void move(String label, long n) {
        try (var t = board.open(label)) {
            t.started(n);
            t.advanced(n);
            t.finished();
        }
    }

    @Test
    void declared_sets_count_their_downloads_and_a_closed_one_keeps_what_it_downloaded() {
        try (var lock = board.plan(3)) {
            move("g:a:1", 10);
            lock.settled(true);
            lock.settled(true);
            try (var sync = board.plan(2)) {
                move("g:b:1", 10);
                sync.settled(true);
                assertThat(board.snapshot().settled()).isEqualTo(3);
                assertThat(board.snapshot().planned()).isEqualTo(5);
            }
            assertThat(board.snapshot().settled())
                    .as("the nested set closed: what it downloaded stays counted")
                    .isEqualTo(3);
            assertThat(board.snapshot().planned())
                    .as("its one file never fetched leaves")
                    .isEqualTo(4);
        }
        assertThat(board.snapshot().settled()).isEqualTo(3);
        assertThat(board.snapshot().planned()).isEqualTo(3);
    }

    @Test
    void a_set_of_files_already_on_disk_is_not_a_download_whatever_runs_beside_it() {
        move("g:earlier:1", 100);
        try (var warm = board.plan(50);
                var beside = board.open("g:jdk:21")) {
            beside.started(1_000);
            beside.advanced(500); // another step downloads at the same time
            for (int i = 0; i < 10; i++) warm.settled(false);
            TransferBoard.Snapshot d = board.snapshot();
            assertThat(d.planned())
                    .as("the warm set adds nothing to the run's download count")
                    .isZero();
            assertThat(d.settled()).isZero();
        }
    }

    @Test
    void a_plan_never_reports_more_settled_than_planned() {
        try (var plan = board.plan(1)) {
            move("g:a:1", 1);
            plan.settled(true);
            plan.settled(true);
            assertThat(board.snapshot().settled()).isEqualTo(1);
        }
    }

    /** A run says how many artifacts it downloaded: their POMs and metadata land too, and do not count. */
    @Test
    void artifacts_are_counted_apart_from_every_other_file() {
        try (var jar = board.openArtifact("g:a:1")) {
            jar.started(10);
            jar.advanced(10);
            jar.finished();
        }
        try (var pom = board.open("g:a:1 · pom")) {
            pom.started(2);
            pom.advanced(2);
            pom.finished();
        }
        try (var gaveUp = board.openArtifact("g:b:1")) {
            gaveUp.started(10);
            gaveUp.advanced(4);
        }

        assertThat(board.artifactsLanded()).isEqualTo(1);
        assertThat(board.snapshot().files()).isEqualTo(2);
    }
}
