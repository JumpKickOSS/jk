// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.notices;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.decisions.Decision;
import cc.jumpkick.engine.decisions.DecisionForms;
import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.engine.decisions.DecisionSurface;
import cc.jumpkick.testing.FakeClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bell's unseen count: notices newer than one seen marker per machine, which only moves
 * forward, is heard by every listener, and survives a restart.
 */
class NoticesTest {

    @TempDir
    Path state;

    private final FakeClock clock = new FakeClock();

    private Notices notices() {
        return new Notices(state.resolve(Notices.FILE), clock);
    }

    @Test
    void the_count_is_what_is_newer_than_the_marker() {
        Notices n = notices();
        Notices.Notice first = n.add("decision", "A needs you: one?", "#decision/1", "1");
        Notices.Notice second = n.add("setup", "Machine setup applied", "#status", null);
        assertThat(n.unseen()).isEqualTo(2);
        assertThat(second.at())
                .as("strictly increasing, even within one millisecond")
                .isGreaterThan(first.at());
        assertThat(n.list()).extracting(Notices.Notice::id).containsExactly(second.id(), first.id());

        assertThat(n.seen(first.at()).unseen()).isEqualTo(1);
        assertThat(n.seen(second.at()).unseen()).isZero();
    }

    @Test
    void the_marker_never_moves_back_nor_past_the_newest_notice() {
        Notices n = notices();
        Notices.Notice only = n.add("decision", "A needs you: one?", "#decision/1", "1");
        n.seen(Long.MAX_VALUE);
        assertThat(n.seenUpTo()).isEqualTo(only.at());
        n.seen(0);
        assertThat(n.seenUpTo()).isEqualTo(only.at());

        clock.advance(Duration.ofSeconds(1));
        n.add("decision", "A needs you: two?", "#decision/2", "2");
        assertThat(n.unseen())
                .as("a notice after a far-future seen still counts")
                .isEqualTo(1);
    }

    @Test
    void every_listener_hears_an_addition_and_a_move_but_not_a_move_that_changes_nothing() {
        Notices n = notices();
        List<String> heard = new ArrayList<>();
        n.onChange(c -> heard.add(c.change() + ":" + c.unseen()));
        n.onChange(c -> heard.add("second tab"));
        Notices.Notice added = n.add("decision", "A needs you: one?", "#decision/1", "1");
        n.seen(added.at());
        n.seen(added.at());
        assertThat(heard).containsExactly("added:1", "second tab", "seen:0", "second tab");
    }

    @Test
    void the_marker_and_the_notices_survive_a_restart() {
        Notices before = notices();
        Notices.Notice one = before.add("decision", "A needs you: one?", "#decision/1", "1");
        before.add("decision", "A needs you: two?", "#decision/2", "2");
        before.seen(one.at());

        Notices after = notices();
        assertThat(after.seenUpTo()).isEqualTo(one.at());
        assertThat(after.unseen()).isEqualTo(1);
        assertThat(after.list()).hasSize(2);
        Notices.Notice three = after.add("decision", "A needs you: three?", "#decision/3", "3");
        assertThat(three.at()).isGreaterThan(after.list().get(1).at());
    }

    @Test
    void an_unreadable_file_starts_empty() throws Exception {
        Files.writeString(state.resolve(Notices.FILE), "{not json");
        Notices n = notices();
        assertThat(n.unseen()).isZero();
        assertThat(n.list()).isEmpty();
        n.add("decision", "A needs you: one?", "#decision/1", "1");
        assertThat(notices().unseen()).as("the next change rewrote it").isEqualTo(1);
    }

    @Test
    void only_the_newest_are_kept() {
        Notices n = new Notices();
        for (int i = 0; i < Notices.KEEP + 5; i++) n.add("decision", "q" + i, "#decision/" + i, null);
        assertThat(n.list()).hasSize(Notices.KEEP);
        assertThat(n.list().getFirst().text()).isEqualTo("q" + (Notices.KEEP + 4));
        assertThat(n.unseen()).isEqualTo(Notices.KEEP);
    }

    @Test
    void each_decision_that_opens_is_one_notice_and_settling_it_adds_none() {
        DecisionQueue queue = new DecisionQueue();
        Notices n = notices();
        n.follow(queue);
        Decision d = queue.open(new DecisionQueue.Ask(
                "disk-clean",
                "Clean jk's cache?",
                List.of(),
                DecisionForms.confirm("disk-clean", "Clean jk's cache?", "Yes"),
                null,
                null,
                null,
                null));
        queue.decline(d.id(), DecisionSurface.DASHBOARD);
        assertThat(n.list()).singleElement().satisfies(notice -> {
            assertThat(notice.kind()).isEqualTo("decision");
            assertThat(notice.decision()).isEqualTo(d.id());
            assertThat(notice.href()).isEqualTo("#decision/" + d.id());
            assertThat(notice.text()).isEqualTo("An agent needs you: Clean jk's cache?");
        });
    }
}
