// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionExpiry;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.SessionJournal;
import cc.jumpkick.engine.sessions.EpisodeInference;
import cc.jumpkick.engine.sessions.SessionLog;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.time.Clock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two engines' queues over one state dir: the first writes every decision beside the sessions while
 * it is the primary, the second adopts them, and the first writes nothing once displaced.
 */
class DecisionAdoptionTest {

    @TempDir
    Path state;

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final Clock clock = new Clock() {
        @Override
        public long millis() {
            return now.get();
        }

        @Override
        public long nanos() {
            return now.get() * 1_000_000L;
        }
    };

    @Test
    void the_successor_adopts_an_open_decision_and_the_store_holds_no_host_key() throws Exception {
        AtomicBoolean primary = new AtomicBoolean(true);
        DecisionQueue first = queue(primary);
        Decision d = first.open(ask(true));
        String key = requireNonNull(first.hostKey(d.id()));
        first.call(d.id(), Map.of("tool", "update", "args", Map.of("apply", true)));

        String stored = Files.readString(record(d.id()), StandardCharsets.UTF_8);
        assertThat(stored)
                .as("only the key's hash is on disk")
                .doesNotContain(key)
                .contains(Hashing.sha256Hex(key));

        primary.set(false);
        DecisionQueue next = queue(new AtomicBoolean(true));
        assertThat(next.adopt()).isEqualTo(1);
        Decision adopted = next.find(d.id()).orElseThrow();
        assertThat(adopted.open()).isTrue();
        assertThat(adopted.question()).isEqualTo(d.question());
        assertThat(adopted.deadline()).isEqualTo(d.deadline());
        assertThat(next.call(d.id())).hasValueSatisfying(c -> assertThat(c).containsEntry("tool", "update"));

        assertThat(first.accept(d.id(), Map.of("answer", "yes"), DecisionSurface.CLI, null))
                .as("a displaced engine records no answer")
                .isInstanceOf(DecisionQueue.Result.Missing.class);
        assertThat(next.accept(d.id(), Map.of("answer", "yes"), DecisionSurface.DASHBOARD, key))
                .as("the link the predecessor handed out still proves the host")
                .isInstanceOfSatisfying(
                        DecisionQueue.Result.Settled.class,
                        s -> assertThat(s.decision().answeredBy()).isEqualTo(DecisionSurface.HOST_URL));
        String fresh = requireNonNull(next.hostKey(d.id()));
        assertThat(fresh).as("an adopted decision gets a key of its own").isNotEqualTo(key);
        assertThat(Files.readString(record(d.id()), StandardCharsets.UTF_8)).doesNotContain(fresh);
    }

    @Test
    void an_accepted_decision_spent_before_the_hand_off_is_not_spent_again() {
        AtomicBoolean primary = new AtomicBoolean(true);
        DecisionQueue first = queue(primary);
        Decision d = first.open(ask(false));
        first.accept(d.id(), Map.of("answer", "yes"), DecisionSurface.CLI, null);
        assertThat(first.spend(d.id())).isTrue();

        primary.set(false);
        DecisionQueue next = queue(new AtomicBoolean(true));
        assertThat(next.find(d.id()).orElseThrow().accepted()).isTrue();
        assertThat(next.spent(d.id())).isTrue();
        assertThat(next.spend(d.id())).isFalse();
        assertThat(next.decline(d.id(), DecisionSurface.CLI, null))
                .as("the first answer still wins")
                .isInstanceOf(DecisionQueue.Result.Already.class);
    }

    @Test
    void a_displaced_engine_cannot_spend_an_accepted_decision() {
        AtomicBoolean primary = new AtomicBoolean(true);
        DecisionQueue first = queue(primary);
        Decision d = first.open(ask(false));
        first.accept(d.id(), Map.of("answer", "yes"), DecisionSurface.CLI, null);

        primary.set(false);
        assertThat(first.spend(d.id())).isFalse();
        DecisionQueue next = queue(new AtomicBoolean(true));
        assertThat(next.spend(d.id())).as("the successor runs it, once").isTrue();
        assertThat(next.spend(d.id())).isFalse();
    }

    @Test
    void a_deadline_that_passed_while_no_engine_ran_settles_expired_on_load() throws Exception {
        DecisionQueue first = queue(new AtomicBoolean(true));
        Decision d = first.open(ask(true), Duration.ofMinutes(1));

        now.addAndGet(Duration.ofMinutes(2).toMillis());
        DecisionQueue next = queue(new AtomicBoolean(true));
        next.adopt();
        assertThat(next.find(d.id()).orElseThrow().state()).isEqualTo(Decision.State.EXPIRED);
        assertThat(Files.readString(record(d.id()), StandardCharsets.UTF_8)).contains("\"state\":\"expired\"");
    }

    @Test
    void the_store_keeps_no_more_decisions_than_the_queue() throws Exception {
        DecisionQueue first = queue(new AtomicBoolean(true));
        for (int i = 0; i <= DecisionQueue.MAX_KEPT; i++) {
            Decision d = first.open(ask(false));
            first.decline(d.id(), DecisionSurface.CLI, null);
            now.incrementAndGet();
        }
        try (Stream<Path> files = Files.list(state.resolve(SessionJournal.DECISIONS_DIR))) {
            assertThat(files.count()).isEqualTo(DecisionQueue.MAX_KEPT);
        }
    }

    @Test
    void a_decision_the_history_prune_removes_leaves_the_queue_and_stays_gone() throws Exception {
        DecisionQueue q = queue(new AtomicBoolean(true));
        Decision settled = q.open(ask(false));
        q.decline(settled.id(), DecisionSurface.CLI, null);
        Decision open = q.open(ask(false));
        long day = Duration.ofDays(1).toMillis();
        for (String id : List.of(settled.id(), open.id())) {
            Files.setLastModifiedTime(record(id), FileTime.fromMillis(now.get() - 10 * day));
        }

        BuildJournal.PruneResult r = new BuildJournal(state).prune(2 * day, 0, now.get(), q::prune);

        assertThat(r.removedEntries()).isEqualTo(1);
        assertThat(q.list()).extracting(Decision::id).containsExactly(open.id());
        assertThat(q.find(settled.id())).isEmpty();
        assertThat(q.hostKey(settled.id())).isNull();
        q.call(settled.id(), Map.of("tool", "update"));
        assertThat(record(settled.id()))
                .as("no later write brings the record back")
                .doesNotExist();
        assertThat(q.prune(open.id())).as("an open decision stays").isFalse();
        assertThat(record(open.id())).exists();
    }

    private Path record(String id) {
        return state.resolve(SessionJournal.DECISIONS_DIR).resolve(id + ".json");
    }

    private DecisionQueue queue(AtomicBoolean primary) {
        SessionLog log = new SessionLog(
                new SessionJournal(state.resolve(SessionJournal.DIR)),
                now::get,
                EpisodeInference.DEFAULT_IDLE_GAP_MILLIS,
                SessionExpiry.DEFAULT.toMillis());
        log.writeWhile(primary::get, run -> {});
        DecisionQueue q = new DecisionQueue(clock);
        q.store(new JournaledDecisions(log));
        return q;
    }

    private static DecisionQueue.Ask ask(boolean trust) {
        return new DecisionQueue.Ask(
                "update",
                "Apply 2 moves?",
                List.of("a 1.0 → 1.1"),
                DecisionForms.confirm("update", "Apply 2 moves?", "Yes, apply them"),
                trust,
                null,
                "claude-code 3f9a",
                null,
                "/ws/app");
    }
}
