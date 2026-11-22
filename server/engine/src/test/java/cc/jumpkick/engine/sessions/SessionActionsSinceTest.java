// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.sessions;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionExpiry;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.SessionJournal;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The savings ledger reads every action since a time: its edits and project, never a run, never an older one. */
class SessionActionsSinceTest {

    private static final long T = 1_700_000_000_000L;
    private static final String LABEL = "claude-code 3f9a";

    @TempDir
    Path root;

    private final AtomicLong now = new AtomicLong(T);

    private SessionLog log(SessionJournal journal) {
        return new SessionLog(
                journal,
                now::get,
                EpisodeInference.DEFAULT_IDLE_GAP_MILLIS,
                SessionExpiry.DEFAULT.toMillis(),
                new ProjectIdMemo(dir -> "/ws/app".equals(dir) ? "p-app" : null));
    }

    @Test
    void the_actions_since_a_time_carry_their_edits_and_project_and_leave_runs_out() {
        SessionJournal journal = new SessionJournal(root.resolve("sessions"));
        SessionLog log = log(journal);
        log.open("mcp", "claude-code", LABEL, "/ws/app");
        log.action(
                "mcp",
                LABEL,
                "claude-code",
                Fact.action(T + 1, 4, "/ws/app", Fact.Kind.GRAPH, true, "", 0),
                "usages",
                "usages PricingRules.weekendUplift",
                "Usages of PricingRules.weekendUplift: 3 in main sources, 0 in tests.",
                0);
        now.set(T + 10_000);
        log.action(
                "mcp",
                LABEL,
                "claude-code",
                Fact.action(T + 10_000, 4, "/ws/app", Fact.Kind.ADD, true, "g:a", 2),
                "deps",
                "deps add g:a, g:b",
                null,
                2);
        String episode = Objects.requireNonNull(log.placeRun("mcp", LABEL, "test", "/ws/app", T + 11_000));
        log.runFinished(BuildRecord.running(28, "test", "/ws/app", "g:app", null, T + 11_000, "9.9", "mcp")
                .abandoned(T + 11_900, "9.9")
                .withEpisode(episode));

        for (SessionLog reader : List.of(log, log(journal))) {
            assertThat(reader.actionsSince(T + 5_000)).singleElement().satisfies(a -> {
                assertThat(a.action().kind()).isEqualTo("deps");
                assertThat(a.action().edits()).isEqualTo(2);
                assertThat(a.action().project()).isEqualTo("/ws/app");
                assertThat(a.projectId()).isEqualTo("p-app");
            });
        }
        assertThat(log.actionsSince(0)).extracting(a -> a.action().kind()).containsExactly("usages", "deps");
    }

    @Test
    void without_a_journal_the_live_sessions_answer() {
        SessionLog memory = new SessionLog(
                null, now::get, EpisodeInference.DEFAULT_IDLE_GAP_MILLIS, SessionExpiry.DEFAULT.toMillis());
        memory.open("mcp", "claude-code", LABEL, "/ws/app");
        memory.action(
                "mcp",
                LABEL,
                "claude-code",
                Fact.action(T, 1, "/ws/app", Fact.Kind.RENAME, true, "Ledger.post", 0),
                "rename",
                "rename Ledger.post → record",
                null,
                0);
        assertThat(memory.actionsSince(T)).extracting(a -> a.action().kind()).containsExactly("rename");
        assertThat(memory.actionsSince(T + 1)).isEmpty();
    }
}
