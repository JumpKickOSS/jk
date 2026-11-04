// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.task.TransferBoard;
import cc.jumpkick.testing.FakeClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The run's downloads priced in time, from no request at all: the recorded rate, then its own. */
class DownloadEtaTest {

    private final FakeClock clock = new FakeClock();
    private final TransferBoard board = new TransferBoard(clock);
    private final List<Long> sent = new ArrayList<>();

    private int landed;

    private void land(int artifacts, long bytesEach) {
        for (int i = 0; i < artifacts; i++) {
            try (var t = board.openArtifact("g:a" + landed++ + ":1")) {
                t.started(bytesEach);
                t.advanced(bytesEach);
                t.finished();
            }
        }
    }

    private void land(int artifacts) {
        land(artifacts, 1);
    }

    @Test
    void nothing_planned_is_nothing_priced() {
        new DownloadEta(board, clock, sent::add, 100).sample();
        assertThat(sent).isEmpty();
    }

    @Test
    void before_anything_lands_the_recorded_rate_prices_the_rest() {
        var plan = board.plan(100);
        var eta = new DownloadEta(board, clock, sent::add, 120);
        var first = board.open("g:first:1");
        first.started(1_000);
        land(1); // the set's first download: from here it is a download, priced
        plan.settled(true);

        eta.sample();

        assertThat(sent).containsExactly(99 * 120L);
        first.close();
        plan.close();
    }

    @Test
    void a_set_settling_files_already_on_disk_is_not_priced() {
        var plan = board.plan(800);
        var eta = new DownloadEta(board, clock, sent::add, 40);
        for (int i = 0; i < 300; i++) plan.settled(false);

        eta.sample();

        assertThat(sent).as("no bytes moved, nothing in flight").isEmpty();
        plan.close();
    }

    @Test
    void with_no_history_nothing_is_priced_until_the_run_measures_itself() {
        var plan = board.plan(100);
        var eta = new DownloadEta(board, clock, sent::add, 0);
        eta.sample();
        assertThat(sent).as("no history, nothing landed").isEmpty();

        clock.advance(Duration.ofSeconds(3));
        land(10, 1_000);
        for (int i = 0; i < 10; i++) plan.settled(true);
        eta.sample();

        // 10 kB in 3 s; 90 files left at the 1 kB the landed ones averaged.
        assertThat(sent).containsExactly(27_000L);
        plan.close();
    }

    @Test
    void the_runs_own_rate_replaces_the_prior_once_enough_has_landed() {
        var plan = board.plan(50);
        land(1, 100); // the set's first download, before the run's rate is measured
        plan.settled(true);
        var eta = new DownloadEta(board, clock, sent::add, 1_000);
        board.open("g:streaming:1").started(-1);
        eta.sample(); // prior: 49 files left, 49 s
        clock.advance(Duration.ofSeconds(2));
        land(20, 100);
        for (int i = 0; i < 20; i++) plan.settled(true);
        eta.sample(); // measured: 1 byte a ms; the one streaming and 28 not started, 100 bytes each

        assertThat(sent).containsExactly(49_000L, 2_900L);
        plan.close();
    }

    @Test
    void a_small_change_is_not_sent_again() {
        var plan = board.plan(100);
        land(1);
        plan.settled(true);
        var eta = new DownloadEta(board, clock, sent::add, 100);
        board.open("g:streaming:1").started(-1);
        eta.sample();
        plan.settled(true);
        eta.sample(); // 9.8 s against 9.9 s: no news

        assertThat(sent).containsExactly(9_900L);
        plan.close();
    }

    @Test
    void artifacts_from_before_the_run_do_not_count_as_its_rate() {
        land(50);
        var plan = board.plan(10);
        land(1);
        plan.settled(true);
        var eta = new DownloadEta(board, clock, sent::add, 200);
        board.open("g:streaming:1").started(-1);
        eta.sample();

        assertThat(sent).as("the prior, not 51 artifacts in no time").containsExactly(9 * 200L);
        plan.close();
    }

    @Test
    void a_large_jar_in_flight_is_priced_at_its_declared_length() {
        var plan = board.plan(10);
        var eta = new DownloadEta(board, clock, sent::add, 0);
        eta.sample();
        var big = board.openArtifact("g:big:1");
        big.started(10_000);
        big.advanced(500);
        clock.advance(Duration.ofSeconds(1));
        land(5, 100);
        for (int i = 0; i < 5; i++) plan.settled(true);
        eta.sample();

        // 1 kB in 1 s. The big one owes 9.5 kB; the 6 files of known size average 1.75 kB (10.5 kB
        // over 5 landed and 1 in flight), so the 4 not started owe 7 kB: not the 100 bytes each
        // the small ones that landed first would say.
        assertThat(sent).containsExactly(16_500L);
        big.close();
        plan.close();
    }
}
