// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The queue: the first answer wins, a deadline expires as declined, and trust needs a person's surface. */
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

    private Decision open(boolean trust) {
        return queue.open(new DecisionQueue.Ask(
                "update",
                "Apply 2 moves?",
                List.of("a 1.0 → 1.1"),
                DecisionForms.confirm("update", "Apply 2 moves?", "Yes, apply them"),
                trust,
                "mcp-3f9a-x",
                "claude-code 3f9a",
                "e1",
                "/ws/app"));
    }

    @Test
    void the_first_answer_wins_and_a_later_one_is_told_how_it_was_settled() {
        Decision d = open(false);
        assertThat(d.id()).hasSize(24);
        assertThat(d.command()).isEqualTo("jk decide " + d.id());
        assertThat(d.deadline() - d.createdAt()).isEqualTo(DecisionQueue.DEADLINE.toMillis());

        DecisionQueue.Result first = queue.accept(d.id(), Map.of("answer", "yes"), DecisionSurface.DASHBOARD, null);
        DecisionQueue.Result second = queue.decline(d.id(), DecisionSurface.CLI, null);

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
    void a_trust_changing_decision_is_accepted_only_where_a_person_answers() {
        Decision d = open(true);
        assertThat(queue.accept(d.id(), Map.of("answer", "yes"), DecisionSurface.DASHBOARD, null))
                .isInstanceOfSatisfying(DecisionQueue.Result.Refused.class, r -> assertThat(r.reason())
                        .contains("jk decide " + d.id()));
        assertThat(queue.accept(d.id(), Map.of("answer", "yes"), DecisionSurface.DASHBOARD, "not-the-key"))
                .isInstanceOf(DecisionQueue.Result.Refused.class);
        assertThat(queue.find(d.id()).orElseThrow().open()).isTrue();

        String key = queue.hostKey(d.id());
        assertThat(queue.accept(d.id(), Map.of("answer", "yes"), DecisionSurface.DASHBOARD, key))
                .isInstanceOfSatisfying(
                        DecisionQueue.Result.Settled.class,
                        s -> assertThat(s.decision().answeredBy()).isEqualTo(DecisionSurface.HOST_URL));
    }

    @Test
    void the_dashboard_may_decline_a_trust_changing_decision_and_a_no_declines() {
        Decision declined = open(true);
        assertThat(queue.decline(declined.id(), DecisionSurface.DASHBOARD, null))
                .isInstanceOf(DecisionQueue.Result.Settled.class);
        Decision no = open(true);
        assertThat(queue.accept(no.id(), Map.of("answer", "no"), DecisionSurface.CLI, null))
                .isInstanceOfSatisfying(
                        DecisionQueue.Result.Settled.class,
                        s -> assertThat(s.decision().state()).isEqualTo(Decision.State.DECLINED));
        assertThat(queue.accept(open(false).id(), Map.of("answer", "maybe"), DecisionSurface.CLI, null))
                .isInstanceOf(DecisionQueue.Result.Invalid.class);
    }

    @Test
    void an_open_decision_expires_as_declined_at_its_deadline_and_says_so() {
        List<Decision> changes = new ArrayList<>();
        queue.onChange(changes::add);
        Decision d = open(false);
        now[0] += DecisionQueue.DEADLINE.toMillis();

        assertThat(queue.find(d.id()).orElseThrow().state()).isEqualTo(Decision.State.EXPIRED);
        assertThat(queue.accept(d.id(), Map.of("answer", "yes"), DecisionSurface.CLI, null))
                .isInstanceOf(DecisionQueue.Result.Already.class);
        assertThat(changes).extracting(Decision::state).containsExactly(Decision.State.OPEN, Decision.State.EXPIRED);
        assertThat(queue.await(d.id(), Duration.ofSeconds(5)).orElseThrow().state())
                .isEqualTo(Decision.State.EXPIRED);
    }

    @Test
    void the_list_puts_open_decisions_first_and_the_map_carries_what_every_surface_needs() {
        Decision settled = open(false);
        queue.decline(settled.id(), DecisionSurface.CLI, null);
        now[0] += 1;
        Decision waiting = open(true);

        assertThat(queue.list()).extracting(Decision::id).containsExactly(waiting.id(), settled.id());
        Map<String, Object> m = waiting.toMap();
        assertThat(m)
                .containsEntry("state", "open")
                .containsEntry("trust", true)
                .containsEntry("label", "claude-code 3f9a")
                .containsEntry("session", "mcp-3f9a-x")
                .containsEntry("episode", "e1")
                .containsEntry("route", "#decision/" + waiting.id())
                .containsKey("form")
                .doesNotContainKey("answeredBy");
        assertThat(m.toString()).doesNotContain(String.valueOf(queue.hostKey(waiting.id())));
        assertThat(queue.openFor("update", "claude-code 3f9a", "/ws/app")).contains(waiting);
    }
}
