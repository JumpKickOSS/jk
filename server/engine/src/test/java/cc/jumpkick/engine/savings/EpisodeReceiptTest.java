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
import cc.jumpkick.prices.PriceTables;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.assertj.core.api.InstanceOfAssertFactories;
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
        act(label, "claude-code", at, kind, tool);
    }

    private void act(String label, String client, long at, Fact.Kind kind, String tool) {
        now.set(at);
        log.action(
                "mcp",
                label,
                client,
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

        Map<String, EpisodeReceipt> receipts = EpisodeReceipt.forSession(journal, log, AgentUsage.NONE, session);

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
        TokensSaved question = claudeQuestion();
        assertThat(s.tokens()).as("its two questions at its agent's rate").isEqualTo(question.plus(question));
        assertThat(receipt.title()).isEqualTo("Working in app");
    }

    /** What one code question saves the session's agent at the shipped rates, on the bench's model. */
    private static TokensSaved claudeQuestion() {
        SavingsRates.Agent agent =
                Objects.requireNonNull(SavingsRates.shipped().agents().get("claude-code"));
        return TokensSaved.of(agent.model(), agent.question().all());
    }

    @Test
    void a_run_is_counted_once_whether_or_not_the_journal_kept_it() {
        act(LABEL, T + 1_000, Fact.Kind.GRAPH, "usages");
        BuildRecord first = run(LABEL, T + 2_000, 21, new BuildRecord.CacheBenefit(10_000, 4_000, 1, 2));
        journaled(first);
        BuildRecord kept = run(LABEL, T + 3_000, 22, new BuildRecord.CacheBenefit(10_000, 6_000, 2, 2));
        journaled(kept);
        BuildRecord unkept = run(LABEL, T + 4_000, 23, new BuildRecord.CacheBenefit(10_000, 5_000, 0, 1));

        EpisodeReceipt journaledReceipt =
                Objects.requireNonNull(EpisodeReceipt.forRun(journal, log, AgentUsage.NONE, kept));
        assertThat(journaledReceipt.saved().runs()).isEqualTo(2);
        assertThat(journaledReceipt.saved().savedMillis()).isEqualTo(10_000);

        EpisodeReceipt withUnkept =
                Objects.requireNonNull(EpisodeReceipt.forRun(journal, log, AgentUsage.NONE, unkept));
        assertThat(withUnkept.saved().runs()).isEqualTo(3);
        assertThat(withUnkept.saved().savedMillis()).isEqualTo(15_000);
    }

    @Test
    void a_run_placed_in_no_episode_has_no_receipt() {
        BuildRecord r = run(LABEL, T + 1_000, 31, null).withEpisode(null);
        assertThat(EpisodeReceipt.forRun(journal, log, AgentUsage.NONE, r)).isNull();
        assertThat(EpisodeReceipt.markdown(journal, log, AgentUsage.NONE, r)).isNull();
    }

    @Test
    void the_markdown_names_the_episode_and_lists_only_what_it_counted_the_time_as_an_estimate() {
        act(LABEL, T + 1_000, Fact.Kind.GRAPH, "usages");
        journaled(run(LABEL, T + 2_000, 41, new BuildRecord.CacheBenefit(200_000, 125_000, 3, 4)));
        BuildRecord last = run(LABEL, T + 3_000, 42, null);
        journaled(last);

        String md = Objects.requireNonNull(EpisodeReceipt.markdown(journal, log, AgentUsage.NONE, last));

        assertThat(md)
                .isEqualTo(EpisodeReceipt.HEADING + "\n\n"
                        + "Working in app · 2 runs\n\n"
                        + "- Build and test time avoided: ~2m 05s (estimate, 75% measured)\n"
                        + "- Steps served from the cache: 2 (2 test suites)\n"
                        + "- Tokens saved: "
                        + claudeQuestion().text(PriceTables.current(), 0) + "\n"
                        + "- Code questions answered: 1\n");
    }

    @Test
    void an_agent_the_bench_never_ran_is_credited_no_tokens_and_the_receipt_says_how_many_calls_it_left_out() {
        String label = "some-new-agent 5c21";
        log.open("mcp", "some-new-agent", label, "/ws/app");
        act(label, "some-new-agent", T + 1_000, Fact.Kind.GRAPH, "usages");
        act(label, "some-new-agent", T + 1_500, Fact.Kind.GRAPH, "query");
        BuildRecord last = run(label, T + 2_000, 61, null);
        journaled(last);

        String md = Objects.requireNonNull(EpisodeReceipt.markdown(journal, log, AgentUsage.NONE, last));

        assertThat(md)
                .contains("- Tokens saved: ~0, ~$0.000 (estimate, API list prices; "
                        + "2 calls not measured by the bench, not counted)\n")
                .contains("- Code questions answered: 2\n");
    }

    @Test
    void the_receipt_ends_with_the_tokens_its_agent_counted_over_the_episode() throws Exception {
        Path claude = root.resolve("claude");
        Path transcript = claude.resolve("projects")
                .resolve(AgentUsage.claudeDir("/ws/app"))
                .resolve("s.jsonl");
        AgentUsageTest.write(
                transcript,
                AgentUsageTest.claudeLine(T - 5_000, "msg_0", "claude-opus-5-5", 999, 0, 0, 999),
                AgentUsageTest.claudeLine(T + 1_500, "msg_1", "claude-opus-5-5", 100, 2_000, 40_000, 900));
        AgentUsage usage = new AgentUsage(Map.of(AgentUsage.Agent.CLAUDE_CODE, claude), () -> true);
        act(LABEL, T + 1_000, Fact.Kind.GRAPH, "usages");
        BuildRecord last = run(LABEL, T + 2_000, 51, null);
        journaled(last);

        String md = Objects.requireNonNull(EpisodeReceipt.markdown(journal, log, usage, last));
        assertThat(md)
                .endsWith("- Code questions answered: 1\n"
                        + "- Tokens the agent used: 43,000 (Claude Code's own count, 40,000 read from its cache)\n");

        Map<String, Object> saved = EpisodeReceipt.forSession(journal, log, usage, session)
                .values()
                .iterator()
                .next()
                .toMap();
        assertThat(saved).containsEntry("graphCalls", 1L);
        assertThat(saved.get("tokensSavedByModel"))
                .as("the question's tokens are priced at the model the agent's own files say it was on")
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .singleElement()
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("model", "claude-opus-5-5");
        @SuppressWarnings("unchecked")
        Map<String, Object> tokens = (Map<String, Object>) saved.get("tokens");
        assertThat(tokens)
                .containsEntry("agent", "Claude Code")
                .containsEntry("wholeSession", false)
                .containsEntry("total", 43_000L)
                .containsEntry("cacheRead", 40_000L);

        assertThat(EpisodeReceipt.markdown(
                        journal, log, new AgentUsage(Map.of(AgentUsage.Agent.CLAUDE_CODE, claude), () -> false), last))
                .as("with reading off the receipt has no line of the agent's own count")
                .doesNotContain("Tokens the agent used");
    }

    @Test
    void a_count_of_a_whole_session_lands_once_on_the_episode_it_ended_in_or_after() throws Exception {
        Path copilot = root.resolve("copilot");
        long gap = EpisodeInference.DEFAULT_IDLE_GAP_MILLIS;
        // One Copilot session ended between the two episodes, another after the last.
        AgentUsageTest.write(
                copilot.resolve("session-state/a/events.jsonl"),
                AgentUsageTest.copilotStart(T, "/ws/app"),
                AgentUsageTest.copilotShutdown(T + 5_000, AgentUsageTest.copilotModel("gpt-5.1", 100, 0, 0, 10)));
        AgentUsageTest.write(
                copilot.resolve("session-state/b/events.jsonl"),
                AgentUsageTest.copilotStart(T + gap, "/ws/app"),
                AgentUsageTest.copilotShutdown(
                        T + 2 * gap + 60_000, AgentUsageTest.copilotModel("gpt-5.1", 200, 0, 0, 20)));
        String label = "github-copilot-cli 51c0";
        String copilotSession = log.open("mcp", "github-copilot-cli", label, "/ws/app");
        act(label, T + 1_000, Fact.Kind.GRAPH, "usages");
        act(label, T + 2 * gap, Fact.Kind.GRAPH, "usages");
        AgentUsage usage = new AgentUsage(Map.of(AgentUsage.Agent.COPILOT, copilot), () -> true);

        Map<String, EpisodeReceipt> receipts = EpisodeReceipt.forSession(journal, log, usage, copilotSession);

        assertThat(receipts).hasSize(2);
        assertThat(receipts.values().stream()
                        .map(r -> Objects.requireNonNull(r.tokens()).sum().total())
                        .sorted()
                        .toList())
                .containsExactly(110L, 220L);
        EpisodeReceipt first = receipts.values().stream()
                .filter(r -> Objects.requireNonNull(r.tokens()).sum().total() == 110L)
                .findFirst()
                .orElseThrow();
        assertThat(first.markdown()).endsWith("- Tokens the agent used: 110 (Copilot's own count, whole session)\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> tokens = (Map<String, Object>) first.toMap().get("tokens");
        assertThat(tokens).containsEntry("wholeSession", true);
    }

    @Test
    void an_episode_that_saved_nothing_says_so() {
        Savings nothing = Savings.empty(Savings.EPISODE, null, "/ws/app", null, T, T);
        String md = new EpisodeReceipt("s.1", "Working in app", nothing, null).markdown();
        assertThat(md).endsWith("Working in app · 0 runs\n\nNothing saved yet: no cache hits, code answers or edits\n");
    }
}
