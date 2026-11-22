// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.savings;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.SessionExpiry;
import cc.jumpkick.engine.http.mcp.McpResults;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.journal.BuildRecord;
import cc.jumpkick.engine.journal.JkResultsMarkdown;
import cc.jumpkick.engine.journal.SessionJournal;
import cc.jumpkick.engine.sessions.EpisodeInference;
import cc.jumpkick.engine.sessions.Fact;
import cc.jumpkick.engine.sessions.SessionLog;
import cc.jumpkick.host.Log;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.run.SlowestTests;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A session file holding a secret in every place one could hide (message text, the model, a usage
 * field, the timestamp, a broken line): only its numbers reach the receipt, and the secret appears
 * in none of jk-results.md, the MCP results payload, the API's receipt, the log, or any file the
 * journal and session log wrote.
 */
class AgentUsageSecretTest {

    private static final String SECRET = "sk-ant-SECRET-4f1d9c";
    private static final long T = Instant.parse("2026-11-21T10:00:00Z").toEpochMilli();
    private static final String LABEL = "claude-code 3f9a";
    private static final String PROJECT = "/ws/app";

    @TempDir
    Path root;

    @Test
    void only_numbers_leave_a_session_file_that_holds_a_secret() throws IOException {
        Path claude = root.resolve("claude");
        String at = Instant.ofEpochMilli(T + 1_500).toString();
        AgentUsageTest.write(
                claude.resolve("projects")
                        .resolve(AgentUsage.claudeDir(PROJECT))
                        .resolve("s.jsonl"),
                // The secret in the message text, in the model, in a usage field, in the timestamp.
                AgentUsageTest.claudeLine(T + 1_500, "msg_a", "claude-opus-5-5", 10, 20, 30, 40)
                        .replace("Looking at PricingRules.", "export TOKEN=" + SECRET),
                AgentUsageTest.claudeLine(T + 1_600, "msg_b", SECRET, 1, 0, 0, 1),
                AgentUsageTest.claudeLine(T + 1_700, "msg_c", "claude-opus-5-5", 1, 0, 0, 1)
                        .replace("\"output_tokens\":1", "\"output_tokens\":\"" + SECRET + "\""),
                AgentUsageTest.claudeLine(T + 1_800, "msg_d", "claude-opus-5-5", 5, 0, 0, 5)
                        .replace(at.substring(0, 4), SECRET),
                "{\"type\":\"assistant\",\"usage\":{\"input_tokens\":" + SECRET);

        AtomicLong now = new AtomicLong(T);
        BuildJournal journal = new BuildJournal(root.resolve("builds"));
        SessionLog log = new SessionLog(
                SessionJournal.under(journal.buildsRoot()),
                now::get,
                EpisodeInference.DEFAULT_IDLE_GAP_MILLIS,
                SessionExpiry.DEFAULT.toMillis());
        String session = log.open("mcp", "claude-code", LABEL, PROJECT);
        now.set(T + 1_000);
        log.action(
                "mcp",
                LABEL,
                "claude-code",
                Fact.action(T + 1_000, 3, PROJECT, Fact.Kind.GRAPH, true, "", 0),
                "usages",
                "usages PricingRules",
                null,
                0);
        now.set(T + 2_000);
        String episode = Objects.requireNonNull(log.placeRun("mcp", LABEL, "test", PROJECT, T + 2_000));
        BuildRecord run = BuildRecord.running(7, "test", PROJECT, "g:app", null, T + 2_000, "9.9", "mcp")
                .abandoned(T + 2_500, "9.9")
                .withEpisode(episode);

        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        Log.install(
                new PrintStream(logged, true, StandardCharsets.UTF_8),
                System.Logger.Level.DEBUG,
                UnaryOperator.identity());
        String receipt;
        String api;
        try {
            AgentUsage usage = new AgentUsage(Map.of(AgentUsage.Agent.CLAUDE_CODE, claude), () -> true);
            receipt = Objects.requireNonNull(EpisodeReceipt.markdown(journal, log, usage, run));
            journal.append(run, BuildJournal.Snapshot.NONE);
            log.runFinished(run);
            Path runDir = Files.createDirectories(root.resolve("builds/run-7"));
            JkResultsMarkdown.write(
                    run,
                    runDir,
                    root.resolve("target/jk-results.md"),
                    List.of(),
                    null,
                    SlowestTests.Ranking.EMPTY,
                    receipt);
            Map<String, Object> mcp =
                    McpResults.read(Map.of("id", "r7"), id -> Optional.of(runDir.resolve("details.jsonl")));
            assertThat(String.valueOf(mcp.get("markdown"))).contains("Tokens the agent used");
            String mcpJson = MiniJson.write(mcp);
            assertThat(mcpJson).doesNotContain(SECRET);
            api = MiniJson.write(Objects.requireNonNull(EpisodeReceipt.forSession(journal, log, usage, session)
                            .get(episode))
                    .toMap());
        } finally {
            Log.install(System.err, System.Logger.Level.INFO, UnaryOperator.identity());
        }

        // Only the first line counts whole; the bad model is unknown, the bad field and timestamp drop.
        assertThat(receipt).contains("- Tokens the agent used: 102 (Claude Code's own count, 30 read from its cache)");
        assertThat(api).contains("\"model\":\"unknown\"").contains("\"total\":102");
        assertThat(logged.toString(StandardCharsets.UTF_8)).contains("s.jsonl:5: not a usage record");

        assertThat(receipt).doesNotContain(SECRET);
        assertThat(api).doesNotContain(SECRET);
        assertThat(logged.toString(StandardCharsets.UTF_8)).doesNotContain(SECRET);
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                if (f.startsWith(claude)) continue;
                assertThat(Files.readString(f, StandardCharsets.ISO_8859_1))
                        .as(f.toString())
                        .doesNotContain(SECRET);
            }
        }
    }

    @Test
    void only_numbers_leave_the_files_of_grok_copilot_muse_and_antigravity() throws IOException {
        String model = "\"" + SECRET + "\"";
        AgentUsageTest.write(
                root.resolve("grok/sessions/%2Fws%2Fapp/s1/usage.json"),
                AgentUsageTest.grokUsage(
                        AgentUsageTest.grokTurn(1, T + 1_000, AgentUsageTest.grokModel("grok-4.5-build", 10, 0, 1)),
                        AgentUsageTest.grokTurn(2, T + 1_100, AgentUsageTest.grokModel(SECRET, 20, 0, 2)),
                        AgentUsageTest.grokTurn(3, T + 1_200, AgentUsageTest.grokModel("grok-4.5-build", 1, 0, 1))
                                .replace("\"outputTokens\":1", "\"outputTokens\":" + model)));
        AgentUsageTest.write(
                root.resolve("copilot/session-state/s1/events.jsonl"),
                AgentUsageTest.copilotStart(T, PROJECT),
                "{\"type\":\"user.message\",\"data\":{\"content\":\"export TOKEN=" + SECRET + "\"}}",
                AgentUsageTest.copilotShutdown(
                        T + 1_500,
                        AgentUsageTest.copilotModel(SECRET, 30, 0, 0, 3) + ","
                                + AgentUsageTest.copilotModel("gpt-5.1", 1, 0, 0, 1)
                                        .replace("\"outputTokens\":1", "\"outputTokens\":" + model)),
                "{\"type\":\"session.shutdown\",\"data\":{\"modelMetrics\":" + SECRET);
        AgentUsageTest.write(
                root.resolve("muse/sessions/2026/11/21/s1/session.jsonl"),
                AgentUsageTest.museMeta("m1", PROJECT),
                AgentUsageTest.museCompleted("c1", T + 1_000, SECRET, 40, 0, 4),
                AgentUsageTest.museCompleted("c2", T + 1_100, "muse-spark-1.3", 1, 0, 1)
                        .replace("\"output_tokens\":1", "\"output_tokens\":" + model),
                "{\"payload_type\":\"runtime.session\",\"payload\":{\"event\":{\"kind\":\"model_completed\","
                        + "\"text\":\"" + SECRET + "\"");
        // The recorded conversation hides the secret in step text, an error and a model's name.
        AgentUsageTest.antigravityConversation(root.resolve("antigravity"), "c1.db");

        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        Log.install(
                new PrintStream(logged, true, StandardCharsets.UTF_8),
                System.Logger.Level.DEBUG,
                UnaryOperator.identity());
        StringBuilder out = new StringBuilder();
        try {
            Map<AgentUsage.Agent, Path> homes = Map.of(
                    AgentUsage.Agent.GROK, root.resolve("grok"),
                    AgentUsage.Agent.COPILOT, root.resolve("copilot"),
                    AgentUsage.Agent.MUSE, root.resolve("muse"),
                    AgentUsage.Agent.ANTIGRAVITY, root.resolve("antigravity"));
            AgentUsage usage = new AgentUsage(homes, () -> true);
            for (String client : List.of("grok", "github-copilot-cli", "muse", "antigravity")) {
                AgentTokens tokens = Objects.requireNonNull(usage.read(client, PROJECT, T, T + 5_000, Long.MAX_VALUE));
                assertThat(tokens.byModel()).as(client).containsKey(AgentTokens.UNKNOWN);
                out.append(tokens.line())
                        .append('\n')
                        .append(MiniJson.write(tokens.toMap()))
                        .append('\n');
            }
        } finally {
            Log.install(System.err, System.Logger.Level.INFO, UnaryOperator.identity());
        }

        assertThat(out.toString())
                .contains("Grok's own count")
                .contains("Copilot's own count")
                .contains("Muse's own count")
                .contains("Antigravity's own count")
                .doesNotContain(SECRET);
        assertThat(logged.toString(StandardCharsets.UTF_8))
                .contains("not a usage record")
                .doesNotContain(SECRET);
    }
}
