// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.wire.protocol.EnvAskFrame;
import java.io.BufferedWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

/**
 * The engine asking a job's client about its shell: one question per name for the whole job, each
 * answer kept for the job, and a client that has gone or stays silent answers nothing — which the
 * suite reads as lost, not as unset.
 */
class ClientAskTest {

    @Test
    void a_name_is_asked_once_and_its_answer_kept() throws Exception {
        StringWriter wire = new StringWriter();
        BufferedWriter writer = new BufferedWriter(wire);
        ClientAsk ask = new ClientAsk(writer, 10_000);
        List<String> asked = new CopyOnWriteArrayList<>();
        Thread client = Thread.ofVirtual().start(() -> {
            // The client: read the question off the wire, answer it on the job's connection.
            while (asked.isEmpty()) {
                wire.toString()
                        .lines()
                        .filter(l -> l.contains("\"env-ask\""))
                        .findFirst()
                        .ifPresent(line -> {
                            asked.add(line);
                            EnvAskFrame q = EnvAskFrame.decode(line);
                            ask.deliver(EnvAskFrame.answer(q.id(), Map.of("A", "1"), null));
                        });
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        assertThat(ask.values(List.of("A", "B"))).isEqualTo(Map.of("A", "1"));
        client.join(5_000);
        assertThat(ask.values(List.of("A", "B"))).as("from what was kept").isEqualTo(Map.of("A", "1"));
        assertThat(asked).hasSize(1);
        assertThat(ask.lost()).isFalse();
        assertThat(ask.toString()).doesNotContain("1\"").doesNotContain("=1");
    }

    @Test
    void a_client_that_is_gone_answers_nothing_and_the_job_knows_it() throws Exception {
        ClientAsk ask = new ClientAsk(new BufferedWriter(new StringWriter()), 10_000);
        CompletableFuture<Map<String, String>> waiting = CompletableFuture.supplyAsync(() -> ask.values(List.of("X")));
        Thread.sleep(100);
        ask.gone();
        assertThat(waiting.get(5, TimeUnit.SECONDS)).isNull();
        assertThat(ask.lost()).isTrue();
        assertThat(ask.names()).isNull();
    }

    @Test
    void a_question_the_client_left_unanswered_is_not_asked_again_and_a_late_answer_is_kept() {
        StringWriter wire = new StringWriter();
        ClientAsk ask = new ClientAsk(new BufferedWriter(wire), 2_000);
        assertThat(ask.names()).as("no answer within the bound").isNull();
        assertThat(ask.values(List.of("A"))).isNull();
        assertThat(ask.lost()).isTrue();

        long start = Clock.SYSTEM.nanos();
        assertThat(ask.names()).as("the open question is not asked again").isNull();
        assertThat(ask.values(List.of("A"))).isNull();
        assertThat(TimeUnit.NANOSECONDS.toMillis(Clock.SYSTEM.nanos() - start))
                .as("no second wait on a question already left unanswered")
                .isLessThan(1_000);
        List<String> questions = questions(wire, 2);
        assertThat(questions).hasSize(2);

        // The client answers after all: later readers get what it said, still without asking.
        for (String line : questions) {
            EnvAskFrame q = EnvAskFrame.decode(line);
            ask.deliver(
                    q.what() == null
                            ? EnvAskFrame.answer(q.id(), Map.of("A", "1"), null)
                            : EnvAskFrame.answer(q.id(), Map.of(), List.of("A", "HOME")));
        }
        assertThat(ask.names()).containsExactlyInAnyOrder("A", "HOME");
        assertThat(ask.values(List.of("A"))).isEqualTo(Map.of("A", "1"));
        assertThat(questions(wire, 3)).hasSize(2);
    }

    /** The questions written on {@code wire} once {@code expected} are there, or after a second. */
    private static List<String> questions(StringWriter wire, int expected) {
        long until = Clock.SYSTEM.nanos() + TimeUnit.SECONDS.toNanos(1);
        List<String> found;
        do {
            found = wire.toString()
                    .lines()
                    .filter(l -> l.contains("\"env-ask\""))
                    .toList();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        } while (found.size() < expected && Clock.SYSTEM.nanos() < until);
        return found;
    }

    @Test
    void a_silent_client_times_out() {
        ClientAsk ask = new ClientAsk(new BufferedWriter(new StringWriter()), 50);
        assertThat(ask.all()).isNull();
        assertThat(ask.lost()).isTrue();
    }
}
