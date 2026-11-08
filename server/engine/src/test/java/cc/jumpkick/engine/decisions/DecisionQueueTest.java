// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The queue: any surface answers, the first answer wins, and a deadline expires as declined. */
class DecisionQueueTest {

    private final long[] now = {1_000_000L};
    private final DecisionQueue queue = new DecisionQueue(new Clock() {
        @Override
        public long millis() {
            return now[0];
        }

        @Override
        public long nanos() {
            return now[0] * 1_000_000L;
        }
    });

    private Decision open() {
        return queue.open(new DecisionQueue.Ask(
                "disk-clean",
                "Clean the cache?",
                List.of("~/.jk/cache 2.1 GiB"),
                DecisionForms.confirm("disk-clean", "Clean the cache?", "Yes, clean it"),
                "mcp-3f9a-x",
                "claude-code 3f9a",
                "e1",
                "/ws/app"));
    }

    @Test
    void the_first_answer_wins_and_a_later_one_is_told_how_it_was_settled() {
        Decision d = open();
        assertThat(d.id()).hasSize(24);
        assertThat(d.command()).isEqualTo("jk decide " + d.id());
        assertThat(d.deadline() - d.createdAt()).isEqualTo(DecisionQueue.DEADLINE.toMillis());

        DecisionQueue.Result first = queue.accept(d.id(), Map.of("answer", "yes"), DecisionSurface.DASHBOARD);
        DecisionQueue.Result second = queue.decline(d.id(), DecisionSurface.CLI);

        assertThat(first).isInstanceOfSatisfying(DecisionQueue.Result.Settled.class, s -> {
            assertThat(s.decision().state()).isEqualTo(Decision.State.ACCEPTED);
            assertThat(s.decision().answeredBy()).isEqualTo(DecisionSurface.DASHBOARD);
        });
        assertThat(second).isInstanceOfSatisfying(DecisionQueue.Result.Already.class, a -> assertThat(
                        a.decision().state())
                .isEqualTo(Decision.State.ACCEPTED));
        assertThat(queue.spend(d.id())).isTrue();
        assertThat(queue.spend(d.id())).as("an accepted decision proceeds once").isFalse();
    }

    @Test
    void every_surface_may_accept_and_the_first_settles_the_others_waiting() throws Exception {
        for (DecisionSurface surface : DecisionSurface.values()) {
            Decision d = open();
            var settled = queue.whenSettled(d.id());
            assertThat(settled).isNotDone();
            assertThat(queue.accept(d.id(), Map.of("answer", "yes"), surface))
                    .as("%s accepts", surface)
                    .isInstanceOfSatisfying(DecisionQueue.Result.Settled.class, s -> {
                        assertThat(s.decision().state()).isEqualTo(Decision.State.ACCEPTED);
                        assertThat(s.decision().answeredBy()).isEqualTo(surface);
                    });
            assertThat(settled.get(5, TimeUnit.SECONDS).accepted())
                    .as("a surface still asking learns the answer")
                    .isTrue();
        }
        assertThat(queue.whenSettled("no-such-decision")).isNotDone();
    }

    @Test
    void the_dashboard_may_decline_and_a_no_declines() {
        Decision declined = open();
        assertThat(queue.decline(declined.id(), DecisionSurface.DASHBOARD))
                .isInstanceOf(DecisionQueue.Result.Settled.class);
        Decision no = open();
        assertThat(queue.accept(no.id(), Map.of("answer", "no"), DecisionSurface.CLI))
                .isInstanceOfSatisfying(
                        DecisionQueue.Result.Settled.class,
                        s -> assertThat(s.decision().state()).isEqualTo(Decision.State.DECLINED));
        assertThat(queue.accept(open().id(), Map.of("answer", "maybe"), DecisionSurface.CLI))
                .isInstanceOf(DecisionQueue.Result.Invalid.class);
    }

    @Test
    void an_open_decision_expires_as_declined_at_its_deadline_and_says_so() {
        List<Decision> changes = new ArrayList<>();
        queue.onChange(changes::add);
        Decision d = open();
        now[0] += DecisionQueue.DEADLINE.toMillis();

        assertThat(queue.find(d.id()).orElseThrow().state()).isEqualTo(Decision.State.EXPIRED);
        assertThat(queue.accept(d.id(), Map.of("answer", "yes"), DecisionSurface.CLI))
                .isInstanceOf(DecisionQueue.Result.Already.class);
        assertThat(changes).extracting(Decision::state).containsExactly(Decision.State.OPEN, Decision.State.EXPIRED);
        assertThat(queue.await(d.id(), Duration.ofSeconds(5)).orElseThrow().state())
                .isEqualTo(Decision.State.EXPIRED);
    }

    @Test
    void the_list_puts_open_decisions_first_and_the_map_carries_what_every_surface_needs() {
        Decision settled = open();
        queue.decline(settled.id(), DecisionSurface.CLI);
        now[0] += 1;
        Decision waiting = open();

        assertThat(queue.list()).extracting(Decision::id).containsExactly(waiting.id(), settled.id());
        Map<String, Object> m = waiting.toMap();
        assertThat(m)
                .containsEntry("state", "open")
                .containsEntry("label", "claude-code 3f9a")
                .containsEntry("session", "mcp-3f9a-x")
                .containsEntry("episode", "e1")
                .containsEntry("route", "#decision/" + waiting.id())
                .containsKey("form")
                .doesNotContainKey("answeredBy");
        assertThat(queue.openFor("disk-clean", "claude-code 3f9a", "/ws/app")).contains(waiting);
    }
}
