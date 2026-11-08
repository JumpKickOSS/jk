// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.decisions.Decision;
import cc.jumpkick.engine.decisions.DecisionForms;
import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.engine.decisions.DecisionSurface;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.wire.protocol.DecisionsAck;
import cc.jumpkick.wire.protocol.DecisionsRequest;
import java.util.List;
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

        DecisionsAck ack =
                DecideVerb.answer(queue, new DecisionsRequest(DecisionsRequest.ACCEPT, d.id(), "{\"answer\":\"yes\"}"));

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

        assertThat(DecideVerb.answer(queue, new DecisionsRequest(DecisionsRequest.DECLINE, d.id(), null))
                        .error())
                .isNull();
        assertThat(DecideVerb.answer(
                                queue, new DecisionsRequest(DecisionsRequest.ACCEPT, d.id(), "{\"answer\":\"yes\"}"))
                        .error())
                .isEqualTo("decision " + d.id() + " is already declined");
    }
}
