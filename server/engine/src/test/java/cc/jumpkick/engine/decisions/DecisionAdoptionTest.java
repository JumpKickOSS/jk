// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.decisions;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionExpiry;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.SessionJournal;
import cc.jumpkick.engine.sessions.EpisodeInference;
import cc.jumpkick.engine.sessions.SessionLog;
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
    void the_successor_adopts_an_open_decision_and_the_dashboard_answers_it_there() throws Exception {
        AtomicBoolean primary = new AtomicBoolean(true);
        DecisionQueue first = queue(primary);
        Decision d = first.open(ask());
        first.call(d.id(), Map.of("tool", "disk", "args", Map.of("action", "clean")));

        primary.set(false);
        DecisionQueue next = queue(new AtomicBoolean(true));
        assertThat(next.adopt()).isEqualTo(1);
        Decision adopted = next.find(d.id()).orElseThrow();
        assertThat(adopted.open()).isTrue();
        assertThat(adopted.question()).isEqualTo(d.question());
        assertThat(adopted.deadline()).isEqualTo(d.deadline());
        assertThat(next.call(d.id())).hasValueSatisfying(c -> assertThat(c).containsEntry("tool", "disk"));

        assertThat(first.accept(d.id(), Map.of("answer", "yes"), DecisionSurface.CLI))
                .as("a displaced engine records no answer")
                .isInstanceOf(DecisionQueue.Result.Missing.class);
        assertThat(next.accept(d.id(), Map.of("answer", "yes"), DecisionSurface.DASHBOARD))
                .isInstanceOfSatisfying(
                        DecisionQueue.Result.Settled.class,
                        s -> assertThat(s.decision().answeredBy()).isEqualTo(DecisionSurface.DASHBOARD));
    }

    @Test
    void an_accepted_decision_spent_before_the_hand_off_is_not_spent_again() {
        AtomicBoolean primary = new AtomicBoolean(true);
        DecisionQueue first = queue(primary);
        Decision d = first.open(ask());
        first.accept(d.id(), Map.of("answer", "yes"), DecisionSurface.CLI);
        assertThat(first.spend(d.id())).isTrue();

        primary.set(false);
        DecisionQueue next = queue(new AtomicBoolean(true));
        assertThat(next.find(d.id()).orElseThrow().accepted()).isTrue();
        assertThat(next.spent(d.id())).isTrue();
        assertThat(next.spend(d.id())).isFalse();
        assertThat(next.decline(d.id(), DecisionSurface.CLI))
                .as("the first answer still wins")
                .isInstanceOf(DecisionQueue.Result.Already.class);
    }

    @Test
    void a_displaced_engine_cannot_spend_an_accepted_decision() {
        AtomicBoolean primary = new AtomicBoolean(true);
        DecisionQueue first = queue(primary);
        Decision d = first.open(ask());
        first.accept(d.id(), Map.of("answer", "yes"), DecisionSurface.CLI);

        primary.set(false);
        assertThat(first.spend(d.id())).isFalse();
        DecisionQueue next = queue(new AtomicBoolean(true));
        assertThat(next.spend(d.id())).as("the successor runs it, once").isTrue();
        assertThat(next.spend(d.id())).isFalse();
    }

    @Test
    void a_deadline_that_passed_while_no_engine_ran_settles_expired_on_load() throws Exception {
        DecisionQueue first = queue(new AtomicBoolean(true));
        Decision d = first.open(ask(), Duration.ofMinutes(1));

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
            Decision d = first.open(ask());
            first.decline(d.id(), DecisionSurface.CLI);
            now.incrementAndGet();
        }
        try (Stream<Path> files = Files.list(state.resolve(SessionJournal.DECISIONS_DIR))) {
            assertThat(files.count()).isEqualTo(DecisionQueue.MAX_KEPT);
        }
    }

    @Test
    void a_decision_the_history_prune_removes_leaves_the_queue_and_stays_gone() throws Exception {
        DecisionQueue q = queue(new AtomicBoolean(true));
        Decision settled = q.open(ask());
        q.decline(settled.id(), DecisionSurface.CLI);
        Decision open = q.open(ask());
        long day = Duration.ofDays(1).toMillis();
        for (String id : List.of(settled.id(), open.id())) {
            Files.setLastModifiedTime(record(id), FileTime.fromMillis(now.get() - 10 * day));
        }

        BuildJournal.PruneResult r = new BuildJournal(state).prune(2 * day, 0, now.get(), q::prune);

        assertThat(r.removedEntries()).isEqualTo(1);
        assertThat(q.list()).extracting(Decision::id).containsExactly(open.id());
        assertThat(q.find(settled.id())).isEmpty();
        q.call(settled.id(), Map.of("tool", "disk"));
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

    private static DecisionQueue.Ask ask() {
        return new DecisionQueue.Ask(
                "disk-clean",
                "Clean the cache?",
                List.of("~/.jk/cache 2.1 GiB"),
                DecisionForms.confirm("disk-clean", "Clean the cache?", "Yes, clean it"),
                null,
                "claude-code 3f9a",
                null,
                "/ws/app");
    }
}
