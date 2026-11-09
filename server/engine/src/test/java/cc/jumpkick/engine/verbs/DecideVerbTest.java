// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.decisions.Decision;
import cc.jumpkick.engine.decisions.DecisionForms;
import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.engine.decisions.DecisionSurface;
import cc.jumpkick.form.Field;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.wire.protocol.DecisionsAck;
import cc.jumpkick.wire.protocol.DecisionsRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** {@code jk decide}'s engine side: its answer settles any decision, whether or not a terminal typed it. */
class DecideVerbTest {

    private final DecisionQueue queue = new DecisionQueue();

    private Decision open() {
        return queue.open(new DecisionQueue.Ask(
                "jdk-uninstall",
                "Uninstall temurin-21?",
                List.of(),
                DecisionForms.confirm("jdk-uninstall", "Uninstall temurin-21?", "Yes, uninstall it"),
                null,
                "claude-code 3f9a",
                null,
                "/ws/app"));
    }

    @Test
    void an_accept_sent_without_a_terminal_settles_the_decision() {
        Decision d = open();

        DecisionsAck ack = DecideVerb.answer(
                queue, new DecisionsRequest(DecisionsRequest.ACCEPT, d.id(), "{\"answer\":\"yes\"}", null));

        assertThat(ack.error()).isNull();
        assertThat(MiniJson.str(MiniJson.parse(Objects.requireNonNull(ack.decision())), "state"))
                .isEqualTo("accepted");
        Decision settled = queue.find(d.id()).orElseThrow();
        assertThat(settled.accepted()).isTrue();
        assertThat(settled.answeredBy()).isEqualTo(DecisionSurface.CLI);
    }

    @Test
    void a_decline_settles_it_and_a_late_answer_is_told_how_it_was_settled() {
        Decision d = open();

        assertThat(DecideVerb.answer(queue, new DecisionsRequest(DecisionsRequest.DECLINE, d.id(), null, null))
                        .error())
                .isNull();
        assertThat(DecideVerb.answer(
                                queue,
                                new DecisionsRequest(DecisionsRequest.ACCEPT, d.id(), "{\"answer\":\"yes\"}", null))
                        .error())
                .isEqualTo("decision " + d.id() + " is already declined");
    }

    @Test
    void raise_opens_a_batch_once_and_spend_proceeds_it_once() {
        String ask = MiniJson.write(Map.of(
                "kind", "setup",
                "question", "Set up this machine",
                "detail", List.of("one prompt"),
                "yes", "Apply",
                "project", "/ws/app",
                "items",
                        List.of(
                                Map.of("id", "exclude-1", "label", "Exclude C:\\src", "help", "why"),
                                Map.of("id", "long-paths", "label", "Long paths", "help", "why"))));
        DecisionsAck raised = DecideVerb.answer(queue, new DecisionsRequest(DecisionsRequest.RAISE, null, null, ask));
        assertThat(raised.error()).isNull();
        String id = MiniJson.str(MiniJson.parse(Objects.requireNonNull(raised.decision())), "id");
        DecisionsAck again = DecideVerb.answer(queue, new DecisionsRequest(DecisionsRequest.RAISE, null, null, ask));
        assertThat(MiniJson.str(MiniJson.parse(Objects.requireNonNull(again.decision())), "id"))
                .isEqualTo(id);

        Decision d = queue.find(Objects.requireNonNull(id)).orElseThrow();
        assertThat(d.form().steps().getFirst().fields())
                .extracting(Field::key)
                .containsExactly(DecisionForms.CHANGES, DecisionForms.ANSWER);
        // jk decide -y accepts with the defaults: every change ticked.
        DecideVerb.answer(queue, new DecisionsRequest(DecisionsRequest.ACCEPT, id, "{\"answer\":\"yes\"}", null));
        assertThat(DecisionForms.chosen(queue.find(id).orElseThrow().answers()))
                .containsExactly("exclude-1", "long-paths");
        // Asking again after the answer and before its use proceeds with the accepted decision.
        DecisionsAck late = DecideVerb.answer(queue, new DecisionsRequest(DecisionsRequest.RAISE, null, null, ask));
        assertThat(MiniJson.str(MiniJson.parse(Objects.requireNonNull(late.decision())), "state"))
                .isEqualTo("accepted");
        assertThat(DecideVerb.answer(queue, new DecisionsRequest(DecisionsRequest.SPEND, id, null, null))
                        .spent())
                .isTrue();
        assertThat(DecideVerb.answer(queue, new DecisionsRequest(DecisionsRequest.SPEND, id, null, null))
                        .spent())
                .isFalse();
        // Once used, asking again opens a fresh decision.
        DecisionsAck fresh = DecideVerb.answer(queue, new DecisionsRequest(DecisionsRequest.RAISE, null, null, ask));
        assertThat(MiniJson.str(MiniJson.parse(Objects.requireNonNull(fresh.decision())), "id"))
                .isNotEqualTo(id);
    }

    @Test
    void a_raise_that_asks_for_an_alert_reaches_the_alerters() {
        List<String> alerted = new ArrayList<>();
        queue.onAlert(d -> alerted.add(d.id()));
        String ask = MiniJson.write(Map.of("kind", "setup", "question", "Q", "alert", true));
        DecisionsAck raised = DecideVerb.answer(queue, new DecisionsRequest(DecisionsRequest.RAISE, null, null, ask));
        assertThat(alerted)
                .containsExactly(MiniJson.str(MiniJson.parse(Objects.requireNonNull(raised.decision())), "id"));
    }
}
