// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.savings;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionExpiry;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.SessionJournal;
import cc.jumpkick.engine.sessions.EpisodeInference;
import cc.jumpkick.engine.sessions.Fact;
import cc.jumpkick.engine.sessions.SessionLog;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What one episode saved: its runs from the journal and its actions from the session log, nobody else's. */
class EpisodeReceiptTest {

    private static final long T = 1_700_000_000_000L;
    private static final String LABEL = "claude-code 3f9a";
    private static final String OTHER = "claude-code 77aa";

    @TempDir
    Path root;

    private final AtomicLong now = new AtomicLong(T);
    private BuildJournal journal;
    private SessionLog log;
    private String session;

    @BeforeEach
    void log() {
        journal = new BuildJournal(root.resolve("builds"));
        log = new SessionLog(
                SessionJournal.under(journal.buildsRoot()),
                now::get,
                EpisodeInference.DEFAULT_IDLE_GAP_MILLIS,
                SessionExpiry.DEFAULT.toMillis());
        session = log.open("mcp", "claude-code", LABEL, "/ws/app");
        log.open("mcp", "claude-code", OTHER, "/ws/app");
    }

    private void act(String label, long at, Fact.Kind kind, String tool) {
        now.set(at);
        log.action(
                "mcp",
                label,
                "claude-code",
                Fact.action(at, 3, "/ws/app", kind, true, "", 0),
                tool,
                tool + " PricingRules",
                null,
                0);
    }

    /** A finished run of {@code label}'s session, placed in its episode, that skipped one suite. */
    private BuildRecord run(String label, long at, long requestId, BuildRecord.@Nullable CacheBenefit benefit) {
        now.set(at);
        String episode = Objects.requireNonNull(log.placeRun("mcp", label, "test", "/ws/app", at));
        return new BuildRecord(
                        null,
                        0L,
                        BuildRecord.SCHEMA,
                        "test",
                        "/ws/app",
                        "g:app",
                        null,
                        at,
                        at + 500,
                        500,
                        true,
                        false,
                        0,
                        "9.9-test",
                        null,
                        List.of(),
                        List.of(new BuildRecord.Task("run-tests", "", "SKIPPED", 1, 0)),
                        List.of(),
                        "mcp",
                        null,
                        null,
                        benefit,
                        false,
                        null,
                        requestId,
                        null,
                        List.of())
                .withEpisode(episode);
    }

    private void journaled(BuildRecord r) {
        journal.append(r, BuildJournal.Snapshot.NONE);
        log.runFinished(r);
    }

    @Test
    void an_episode_counts_its_own_runs_and_actions_and_nobody_elses() {
        act(LABEL, T + 1_000, Fact.Kind.GRAPH, "usages");
        act(LABEL, T + 2_000, Fact.Kind.GRAPH, "query");
        act(OTHER, T + 2_500, Fact.Kind.GRAPH, "find");
        journaled(run(LABEL, T + 3_000, 11, new BuildRecord.CacheBenefit(90_000, 60_000, 3, 4)));
        journaled(run(OTHER, T + 4_000, 12, new BuildRecord.CacheBenefit(50_000, 40_000, 1, 1)));

        Map<String, EpisodeReceipt> receipts = EpisodeReceipt.forSession(journal, log, session);

        assertThat(receipts).hasSize(1);
        EpisodeReceipt receipt = receipts.values().iterator().next();
        Savings s = receipt.saved();
        assertThat(s.scope()).isEqualTo(Savings.EPISODE);
        assertThat(s.runs()).isEqualTo(1);
        assertThat(s.savedMillis())
                .as("the other session's run is not this episode's")
                .isEqualTo(60_000);
        assertThat(s.stepsSkipped()).isEqualTo(1);
        assertThat(s.suitesSkipped()).isEqualTo(1);
        assertThat(s.graphCalls())
                .as("its two questions, not the other session's")
                .isEqualTo(2);
        assertThat(receipt.title()).isEqualTo("Working in app");
    }

    @Test
    void a_run_is_counted_once_whether_or_not_the_journal_kept_it() {
        act(LABEL, T + 1_000, Fact.Kind.GRAPH, "usages");
        BuildRecord first = run(LABEL, T + 2_000, 21, new BuildRecord.CacheBenefit(10_000, 4_000, 1, 2));
        journaled(first);
        BuildRecord kept = run(LABEL, T + 3_000, 22, new BuildRecord.CacheBenefit(10_000, 6_000, 2, 2));
        journaled(kept);
        BuildRecord unkept = run(LABEL, T + 4_000, 23, new BuildRecord.CacheBenefit(10_000, 5_000, 0, 1));

        EpisodeReceipt journaledReceipt = Objects.requireNonNull(EpisodeReceipt.forRun(journal, log, kept));
        assertThat(journaledReceipt.saved().runs()).isEqualTo(2);
        assertThat(journaledReceipt.saved().savedMillis()).isEqualTo(10_000);

        EpisodeReceipt withUnkept = Objects.requireNonNull(EpisodeReceipt.forRun(journal, log, unkept));
        assertThat(withUnkept.saved().runs()).isEqualTo(3);
        assertThat(withUnkept.saved().savedMillis()).isEqualTo(15_000);
    }

    @Test
    void a_run_placed_in_no_episode_has_no_receipt() {
        BuildRecord r = run(LABEL, T + 1_000, 31, null).withEpisode(null);
        assertThat(EpisodeReceipt.forRun(journal, log, r)).isNull();
        assertThat(EpisodeReceipt.markdown(journal, log, r)).isNull();
    }

    @Test
    void the_markdown_names_the_episode_and_lists_only_what_it_counted_the_time_as_an_estimate() {
        act(LABEL, T + 1_000, Fact.Kind.GRAPH, "usages");
        journaled(run(LABEL, T + 2_000, 41, new BuildRecord.CacheBenefit(200_000, 125_000, 3, 4)));
        BuildRecord last = run(LABEL, T + 3_000, 42, null);
        journaled(last);

        String md = Objects.requireNonNull(EpisodeReceipt.markdown(journal, log, last));

        assertThat(md)
                .isEqualTo(EpisodeReceipt.HEADING + "\n\n"
                        + "Working in app · 2 runs\n\n"
                        + "- Build and test time avoided: ~2m 05s (estimate, 75% measured)\n"
                        + "- Steps served from the cache: 2 (2 test suites)\n"
                        + "- Code questions answered: 1\n");
    }

    @Test
    void an_episode_that_saved_nothing_says_so() {
        Savings nothing = Savings.empty(Savings.EPISODE, null, "/ws/app", null, T, T);
        String md = new EpisodeReceipt("s.1", "Working in app", nothing).markdown();
        assertThat(md).endsWith("Working in app · 0 runs\n\nNothing saved yet: no cache hits, code answers or edits\n");
    }
}
