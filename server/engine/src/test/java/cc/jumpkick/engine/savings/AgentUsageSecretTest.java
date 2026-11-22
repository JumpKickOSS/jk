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
            AgentUsage usage = new AgentUsage(claude, null, () -> true);
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
}
