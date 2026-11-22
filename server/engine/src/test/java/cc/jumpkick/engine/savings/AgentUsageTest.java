// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.savings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The tokens an agent counted in its own session files: recorded samples, never a live agent's. */
class AgentUsageTest {

    private static final long T = Instant.parse("2026-11-21T10:00:00Z").toEpochMilli();
    private static final String PROJECT = "/ws/app";

    @TempDir
    Path root;

    private Path claude() {
        return home(AgentUsage.Agent.CLAUDE_CODE);
    }

    private Path codex() {
        return home(AgentUsage.Agent.CODEX);
    }

    private AgentUsage usage(AtomicBoolean on) {
        Map<AgentUsage.Agent, Path> homes = new EnumMap<>(AgentUsage.Agent.class);
        for (AgentUsage.Agent a : AgentUsage.Agent.values()) {
            homes.put(a, root.resolve(a.name().toLowerCase(Locale.ROOT)));
        }
        return new AgentUsage(homes, on::get);
    }

    private Path home(AgentUsage.Agent agent) {
        return root.resolve(agent.name().toLowerCase(Locale.ROOT));
    }

    /** What the agent {@code client} counted in {@code PROJECT} from {@code since} to {@code until}, the last episode. */
    private AgentTokens read(String client, long since, long until) {
        return Objects.requireNonNull(usage().read(client, PROJECT, since, until, Long.MAX_VALUE));
    }

    private AgentUsage usage() {
        return usage(new AtomicBoolean(true));
    }

    static String at(long millis) {
        return Instant.ofEpochMilli(millis).toString();
    }

    /** One Claude Code assistant line: message {@code id}, its model, and its usage. */
    static String claudeLine(long at, String id, String model, long in, long write, long read, long out) {
        return "{\"parentUuid\":\"p\",\"type\":\"assistant\",\"timestamp\":\"" + at(at) + "\",\"requestId\":\"req_"
                + id + "\",\"message\":{\"id\":\"" + id + "\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\""
                + model + "\",\"content\":[{\"type\":\"text\",\"text\":\"Looking at PricingRules.\"}],\"usage\":{"
                + "\"input_tokens\":" + in + ",\"cache_creation_input_tokens\":" + write
                + ",\"cache_read_input_tokens\":" + read + ",\"output_tokens\":" + out
                + ",\"service_tier\":\"standard\"}}}";
    }

    static Path write(Path file, String... lines) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, String.join("\n", lines) + "\n");
        return file;
    }

    private Path claudeSession(String name, String... lines) throws IOException {
        return write(
                claude().resolve("projects")
                        .resolve(AgentUsage.claudeDir(PROJECT))
                        .resolve(name),
                lines);
    }

    @Test
    void claude_code_counts_each_message_once_within_the_episode_by_model() throws IOException {
        claudeSession(
                "s1.jsonl",
                claudeLine(T - 60_000, "msg_before", "claude-opus-5-5", 9, 9, 9, 9),
                "{\"type\":\"user\",\"timestamp\":\"" + at(T + 500)
                        + "\",\"message\":{\"role\":\"user\",\"content\":\"hi\"}}",
                claudeLine(T + 1_000, "msg_a", "claude-opus-5-5", 10, 200, 3_000, 5),
                // The same message again, its output complete: the last line counts, once.
                claudeLine(T + 1_200, "msg_a", "claude-opus-5-5", 10, 200, 3_000, 40),
                claudeLine(T + 2_000, "msg_b", "claude-haiku-5-5", 7, 0, 100, 3),
                claudeLine(T + 9_000, "msg_after", "claude-opus-5-5", 9, 9, 9, 9));
        write(
                claude().resolve("projects")
                        .resolve(AgentUsage.claudeDir(PROJECT))
                        .resolve("s1/subagents/agent-1.jsonl"),
                claudeLine(T + 3_000, "msg_sub", "claude-haiku-5-5", 1, 0, 0, 1));

        AgentTokens tokens = read("claude-code", T, T + 5_000);

        assertThat(tokens.agent()).isEqualTo("Claude Code");
        assertThat(tokens.byModel())
                .containsEntry("claude-opus-5-5", new AgentTokens.Counts(10, 200, 3_000, 40))
                .containsEntry("claude-haiku-5-5", new AgentTokens.Counts(8, 0, 100, 4))
                .hasSize(2);
        assertThat(tokens.sum().total()).isEqualTo(3_250 + 112);
        assertThat(tokens.line())
                .isEqualTo("Tokens the agent used: 3,362 (Claude Code's own count, 3,100 read from its cache)");
    }

    @Test
    void the_models_the_agent_ran_on_are_read_by_time_without_one_it_could_not_name() throws IOException {
        claudeSession(
                "s1.jsonl",
                claudeLine(T - 60_000, "msg_before", "claude-opus-5-5", 9, 9, 9, 9),
                claudeLine(T + 1_000, "msg_a", "claude-sonnet-5-5", 1, 1, 1, 1),
                claudeLine(T + 2_000, "msg_b", "<synthetic>", 0, 0, 0, 0),
                claudeLine(T + 3_000, "msg_c", "claude-opus-5-5", 1, 1, 1, 1));

        assertThat(usage().models("claude-code", PROJECT, T))
                .containsExactly(Map.entry(T + 1_000, "claude-sonnet-5-5"), Map.entry(T + 3_000, "claude-opus-5-5"));
        assertThat(usage(new AtomicBoolean(false)).models("claude-code", PROJECT, T))
                .as("nothing is read while reading is off")
                .isEmpty();
        assertThat(usage().models("cursor-agent", PROJECT, T)).isEmpty();

        AgentModels models = new AgentModels(usage());
        models.note("claude-code", PROJECT, T + 500);
        assertThat(models.at("claude-code", PROJECT, T + 500))
                .as("before its first count from there on, the model of that count")
                .isEqualTo("claude-sonnet-5-5");
        assertThat(models.at("claude-code", PROJECT, T + 2_500))
                .as("the model of its last count at or before the call")
                .isEqualTo("claude-sonnet-5-5");
        assertThat(models.at("claude-code", PROJECT, T + 4_000)).isEqualTo("claude-opus-5-5");
        assertThat(models.at(null, PROJECT, T + 4_000)).isNull();
    }

    @Test
    void claude_code_reads_only_the_checkout_of_the_episode() throws IOException {
        write(
                claude().resolve("projects")
                        .resolve(AgentUsage.claudeDir("/ws/other"))
                        .resolve("s.jsonl"),
                claudeLine(T + 1_000, "msg_x", "claude-opus-5-5", 1, 1, 1, 1));
        assertThat(usage().read("claude-code", PROJECT, T, T + 5_000, Long.MAX_VALUE))
                .isNull();
        assertThat(AgentUsage.claudeDir("/Users/me/src/my.app")).isEqualTo("-Users-me-src-my-app");
    }

    @Test
    void codex_counts_the_growth_of_its_running_total_in_its_own_checkout() throws IOException {
        String meta = "{\"timestamp\":\"" + at(T) + "\",\"type\":\"session_meta\",\"payload\":{\"id\":\"x\",\"cwd\":\""
                + PROJECT + "\",\"instructions\":\"be brief\"}}";
        String turn = "{\"timestamp\":\"" + at(T) + "\",\"type\":\"turn_context\",\"payload\":{\"cwd\":\"" + PROJECT
                + "\",\"model\":\"gpt-5.1-codex\"}}";
        write(
                codex().resolve("sessions/2026/11/21/rollout-a.jsonl"),
                meta,
                turn,
                tokenCount(T - 1_000, 100, 40, 10),
                tokenCount(T + 1_000, 1_100, 840, 60),
                // Repeated as it was: nothing new.
                tokenCount(T + 1_100, 1_100, 840, 60),
                tokenCount(T + 2_000, 1_600, 1_240, 90),
                tokenCount(T + 9_000, 9_000, 9_000, 900));
        write(
                codex().resolve("sessions/2026/11/21/rollout-b.jsonl"),
                meta.replace(PROJECT, "/ws/other"),
                turn,
                tokenCount(T + 1_000, 500, 0, 50));

        AgentTokens tokens = read("codex-mcp-client", T, T + 5_000);

        assertThat(tokens.agent()).isEqualTo("Codex");
        // 1,500 more input of which 1,200 more cached; 80 more output.
        assertThat(tokens.byModel()).containsOnlyKeys("gpt-5.1-codex");
        assertThat(tokens.byModel().get("gpt-5.1-codex")).isEqualTo(new AgentTokens.Counts(300, 0, 1_200, 80));
    }

    static String tokenCount(long at, long input, long cached, long output) {
        return "{\"timestamp\":\"" + at(at)
                + "\",\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":{"
                + "\"total_token_usage\":{\"input_tokens\":" + input + ",\"cached_input_tokens\":" + cached
                + ",\"output_tokens\":" + output + ",\"reasoning_output_tokens\":0,\"total_tokens\":"
                + (input + output) + "},\"last_token_usage\":{\"input_tokens\":1}}}}";
    }

    @Test
    void nothing_is_read_when_it_is_off_for_an_agent_it_does_not_know_or_with_no_files() throws IOException {
        claudeSession("s1.jsonl", claudeLine(T + 1_000, "msg_a", "claude-opus-5-5", 1, 1, 1, 1));
        AtomicBoolean on = new AtomicBoolean(false);
        assertThat(usage(on).read("claude-code", PROJECT, T, T + 5_000, Long.MAX_VALUE))
                .isNull();
        on.set(true);
        assertThat(usage(on).read("cursor-agent", PROJECT, T, T + 5_000, Long.MAX_VALUE))
                .isNull();
        assertThat(usage(on).read(null, PROJECT, T, T + 5_000, Long.MAX_VALUE)).isNull();
        assertThat(usage(on).read("codex", PROJECT, T, T + 5_000, Long.MAX_VALUE))
                .isNull();
        assertThat(AgentUsage.NONE.read("claude-code", PROJECT, T, T + 5_000, Long.MAX_VALUE))
                .isNull();
    }

    @Test
    void a_file_last_written_before_the_episode_is_not_read_and_a_changed_one_is_read_again() throws IOException {
        Path file = claudeSession("s1.jsonl", claudeLine(T + 1_000, "msg_a", "claude-opus-5-5", 1, 0, 0, 1));
        Files.setLastModifiedTime(file, FileTime.fromMillis(T - 1));
        AgentUsage usage = usage();
        assertThat(usage.read("claude-code", PROJECT, T, T + 5_000, Long.MAX_VALUE))
                .isNull();

        Files.writeString(
                file, claudeLine(T + 2_000, "msg_b", "claude-opus-5-5", 2, 0, 0, 2) + "\n", StandardOpenOption.APPEND);
        Files.setLastModifiedTime(file, FileTime.fromMillis(T + 2_000));
        assertThat(Objects.requireNonNull(usage.read("claude-code", PROJECT, T, T + 5_000, Long.MAX_VALUE))
                        .sum()
                        .total())
                .isEqualTo(6);
    }

    /** One Grok turn: its end and, per model, input (cache included), cache reads and output. */
    static String grokTurn(int number, long endedAt, String models) {
        return "{\"turnNumber\":" + number + ",\"endedAt\":\"" + at(endedAt).replace("Z", "+00:00")
                + "\",\"inputTokens\":1,\"primaryModelId\":\"grok-4.5-build\",\"modelUsage\":{" + models + "}}";
    }

    static String grokModel(String model, long input, long read, long output) {
        return "\"" + model + "\":{\"inputTokens\":" + input + ",\"outputTokens\":" + output
                + ",\"cachedReadTokens\":" + read + ",\"cacheCreationTokens\":0,\"reasoningTokens\":3,"
                + "\"totalTokens\":" + (input + output) + ",\"modelCalls\":2,\"costUsdTicks\":7513232000}";
    }

    static String grokUsage(String... turns) {
        return "{\"sessionId\":\"01a1f6f2\",\"updatedAt\":\"2026-11-21T10:00:09.523636+00:00\",\"session\":{"
                + "\"inputTokens\":1},\"turns\":[" + String.join(",", turns) + "]}";
    }

    @Test
    void grok_counts_each_turn_in_the_episode_by_model_in_its_own_checkout() throws IOException {
        Path sessions = home(AgentUsage.Agent.GROK).resolve("sessions");
        write(
                sessions.resolve("%2Fws%2Fapp/01a1f6f2/usage.json"),
                grokUsage(
                        grokTurn(1, T - 60_000, grokModel("grok-4.5-build", 9, 0, 9)),
                        grokTurn(2, T + 1_000, grokModel("grok-4.5-build", 1_100, 900, 20)),
                        grokTurn(
                                3,
                                T + 2_000,
                                grokModel("grok-4.5-build", 200, 100, 5) + ","
                                        + grokModel("grok-code-fast-2", 50, 0, 2)),
                        grokTurn(4, T + 9_000, grokModel("grok-4.5-build", 9, 0, 9))));
        write(
                sessions.resolve("%2Fws%2Fother/01a1fb5c/usage.json"),
                grokUsage(grokTurn(1, T + 1_000, grokModel("grok-4.5-build", 500, 0, 50))));

        AgentTokens tokens = read("grok", T, T + 5_000);

        assertThat(tokens.agent()).isEqualTo("Grok");
        assertThat(tokens.byModel())
                .containsEntry("grok-4.5-build", new AgentTokens.Counts(300, 0, 1_000, 25))
                .containsEntry("grok-code-fast-2", new AgentTokens.Counts(50, 0, 0, 2))
                .hasSize(2);
        assertThat(GrokUsage.checkout(sessions.resolve("%2FUsers%2Fme%2Fmy+app/s/usage.json")))
                .isEqualTo("/Users/me/my+app");
    }

    static String copilotStart(long at, String cwd) {
        return "{\"type\":\"session.start\",\"data\":{\"sessionId\":\"3d08\",\"copilotVersion\":\"1.0.114\","
                + "\"context\":{\"cwd\":\"" + cwd + "\",\"gitRoot\":\"" + cwd + "\"}},\"id\":\"e1\",\"timestamp\":\""
                + at(at) + "\",\"parentId\":null}";
    }

    static String copilotShutdown(long at, String models) {
        return "{\"type\":\"session.shutdown\",\"data\":{\"shutdownType\":\"routine\",\"totalPremiumRequests\":3,"
                + "\"totalApiDurationMs\":5000,\"sessionStartTime\":" + T + ",\"codeChanges\":{\"linesAdded\":1,"
                + "\"linesRemoved\":0,\"filesModified\":[\"src/App.java\"]},\"modelMetrics\":{" + models
                + "},\"currentModel\":\"claude-sonnet-5-5\"},\"id\":\"e9\",\"timestamp\":\"" + at(at)
                + "\",\"parentId\":\"e8\"}";
    }

    static String copilotModel(String model, long input, long read, long write, long output) {
        return "\"" + model + "\":{\"requests\":{\"count\":4,\"cost\":1},\"usage\":{\"inputTokens\":" + input
                + ",\"outputTokens\":" + output + ",\"cacheReadTokens\":" + read + ",\"cacheWriteTokens\":" + write
                + "}}";
    }

    @Test
    void copilot_counts_a_whole_session_at_its_end_and_a_resumed_one_by_its_growth() throws IOException {
        Path state = home(AgentUsage.Agent.COPILOT).resolve("session-state");
        write(
                state.resolve("3d08/events.jsonl"),
                copilotStart(T, PROJECT),
                "{\"type\":\"user.message\",\"data\":{\"content\":\"fix the \\\"session.shutdown\\\" test\"},"
                        + "\"id\":\"e2\",\"timestamp\":\"" + at(T + 500) + "\"}",
                copilotShutdown(T + 7_000, copilotModel("claude-sonnet-5-5", 2_000, 1_500, 300, 40)),
                copilotStart(T + 20_000, PROJECT),
                copilotShutdown(
                        T + 30_000,
                        copilotModel("claude-sonnet-5-5", 2_500, 1_800, 300, 60) + ","
                                + copilotModel("gpt-5.1", 100, 0, 0, 10)));
        write(
                state.resolve("9f00/events.jsonl"),
                copilotStart(T, "/ws/other"),
                copilotShutdown(T + 4_000, copilotModel("claude-sonnet-5-5", 900, 0, 0, 9)));
        AgentUsage usage = usage();

        // The first episode runs to when the next starts; the session ended between them.
        AgentTokens first = Objects.requireNonNull(usage.read("github-copilot-cli", PROJECT, T, T + 5_000, T + 10_000));
        assertThat(first.agent()).isEqualTo("Copilot");
        assertThat(first.byModel())
                .containsOnly(Map.entry("claude-sonnet-5-5", new AgentTokens.Counts(200, 300, 1_500, 40)));
        AgentTokens last = Objects.requireNonNull(
                usage.read("github-copilot-cli", PROJECT, T + 10_000, T + 25_000, Long.MAX_VALUE));
        assertThat(last.byModel())
                .containsEntry("claude-sonnet-5-5", new AgentTokens.Counts(200, 0, 300, 20))
                .containsEntry("gpt-5.1", new AgentTokens.Counts(100, 0, 0, 10))
                .hasSize(2);
    }

    static String museRecord(String id, long at, String payloadType, String payload) {
        return "{\"schema_version\":1,\"id\":\"" + id + "\",\"stream\":{\"kind\":\"session\",\"id\":\"01a0\"},"
                + "\"sequence\":3,\"recorded_at\":" + at * 1000
                + ",\"record_type\":\"event\",\"durability\":\"durable\","
                + "\"payload_type\":\"" + payloadType + "\",\"payload_schema_version\":1,\"payload\":" + payload + "}";
    }

    static String museMeta(String id, String cwd) {
        return museRecord(
                id,
                T,
                "runtime.session.metadata",
                "{\"kind\":\"metadata\",\"record\":{\"workspace_root\":\"" + cwd + "\",\"provider_id\":\"meta\"}}");
    }

    static String museCompleted(String id, long at, String model, long input, long read, long output) {
        return museRecord(
                id,
                at,
                "runtime.session",
                "{\"kind\":\"run\",\"run_id\":\"r1\",\"event\":{\"kind\":\"model_completed\",\"usage\":{"
                        + "\"input_tokens\":" + input + ",\"output_tokens\":" + output + ",\"cached_tokens\":" + read
                        + ",\"cache_write_tokens\":0,\"cache_read_tokens\":" + read + ",\"reasoning_tokens\":7},"
                        + "\"duration_ms\":3932,\"finish_reason\":\"tool_calls\",\"model\":\"" + model + "\"}}");
    }

    @Test
    void muse_counts_each_model_call_and_review_once_in_its_own_checkout_subagents_included() throws IOException {
        Path sessions = home(AgentUsage.Agent.MUSE).resolve("sessions");
        String review = museRecord(
                "rv1",
                T + 3_000,
                "runtime.session",
                "{\"kind\":\"approval\",\"event\":{\"kind\":\"automated_review_completed\",\"status\":\"approved\","
                        + "\"assessment\":{\"rationale\":\"reads a file\"},\"model\":{\"provider_id\":\"meta\","
                        + "\"model_id\":\"muse-spark-1.3\"},\"usage\":{\"input_tokens\":400,\"cached_input_tokens\":100,"
                        + "\"non_cached_input_tokens\":300,\"output_tokens\":8,\"total_tokens\":408}}}");
        write(
                sessions.resolve("2026/11/21/01a0/session.jsonl"),
                museMeta("m1", PROJECT),
                museCompleted("c0", T - 60_000, "muse-spark-1.3", 9, 0, 9),
                museCompleted("c1", T + 1_000, "muse-spark-1.3", 1_000, 600, 30),
                // The same record again: counted once.
                museCompleted("c1", T + 1_000, "muse-spark-1.3", 1_000, 600, 30),
                review,
                museCompleted("c9", T + 9_000, "muse-spark-1.3", 9, 0, 9));
        write(
                sessions.resolve("2026/11/21/01a0/subagent/01a1/session.jsonl"),
                // A subagent's metadata names no checkout: it works in its parent's.
                museRecord(
                        "m2",
                        T,
                        "runtime.session.metadata",
                        "{\"kind\":\"metadata\",\"record\":{\"provider_id\":\"meta\",\"model_id\":\"muse-spark-1.3\"}}"),
                museCompleted("s1", T + 2_000, "muse-spark-1.3-mini", 50, 0, 5));
        write(
                sessions.resolve("2026/11/21/02b0/session.jsonl"),
                museMeta("m3", "/ws/other"),
                museCompleted("o1", T + 2_000, "muse-spark-1.3", 70, 0, 7));

        AgentTokens tokens = read("muse", T, T + 5_000);

        assertThat(tokens.agent()).isEqualTo("Muse");
        assertThat(tokens.byModel())
                .containsEntry("muse-spark-1.3", new AgentTokens.Counts(400 + 300, 0, 600 + 100, 30 + 8))
                .containsEntry("muse-spark-1.3-mini", new AgentTokens.Counts(50, 0, 0, 5))
                .hasSize(2);
    }

    /** Copies the recorded Antigravity conversation, and its log, into the agent's home. */
    private void antigravityConversation(String name) throws IOException {
        antigravityConversation(home(AgentUsage.Agent.ANTIGRAVITY), name);
    }

    /** Copies the recorded Antigravity conversation, and its log, into {@code home}'s conversations. */
    static void antigravityConversation(Path home, String name) throws IOException {
        Path dir = Files.createDirectories(home.resolve("conversations"));
        for (String suffix : new String[] {"", "-wal"}) {
            try (var in = Objects.requireNonNull(
                    AgentUsageTest.class.getResourceAsStream("/agent-usage/antigravity/conversation.db" + suffix))) {
                Path f = dir.resolve(name + suffix);
                Files.copy(in, f);
                Files.setLastModifiedTime(f, FileTime.fromMillis(T + 5_000));
            }
        }
    }

    @Test
    void antigravity_counts_each_call_once_from_its_database_and_its_log() throws IOException {
        antigravityConversation("c1.db");

        AgentTokens tokens = read("antigravity", T, T + 5_000);

        assertThat(tokens.agent()).isEqualTo("Antigravity");
        // The first call is in the database, the second and third only in its log; the third's
        // model is not in a model id's shape.
        assertThat(tokens.byModel())
                .containsEntry("gemini-4-neon", new AgentTokens.Counts(100, 0, 1_000, 10))
                .containsEntry("claude-opus-5-5", new AgentTokens.Counts(50, 20, 500, 5))
                .containsEntry(AgentTokens.UNKNOWN, new AgentTokens.Counts(7, 0, 0, 1))
                .hasSize(3);
        assertThat(usage().read("agy", "/ws/other", T, T + 5_000, Long.MAX_VALUE))
                .isNull();
    }

    @Test
    void the_sqlite_reader_sees_every_row_of_a_table_past_interior_and_overflow_pages() throws IOException {
        antigravityConversation("c1.db");
        Path db = home(AgentUsage.Agent.ANTIGRAVITY).resolve("conversations/c1.db");
        try (SqliteFile file = SqliteFile.open(db)) {
            // 44 steps in the database, 3 more only in its log.
            assertThat(file.column("steps", 4)).hasSize(47).allMatch(v -> v instanceof byte[]);
            assertThat(file.column("steps", 1)).containsOnly(1L);
            assertThat(file.column("gen_metadata", 1)).hasSize(5);
            assertThat(file.column("no_such_table", 0)).isEmpty();
        }
        Files.delete(db.resolveSibling("c1.db-wal"));
        try (SqliteFile file = SqliteFile.open(db)) {
            assertThat(file.column("steps", 4)).hasSize(44);
        }
    }

    @Test
    void the_agent_is_known_by_its_client_name() {
        assertThat(AgentUsage.agentOf("claude-code")).isEqualTo(AgentUsage.Agent.CLAUDE_CODE);
        assertThat(AgentUsage.agentOf("codex-mcp-client")).isEqualTo(AgentUsage.Agent.CODEX);
        assertThat(AgentUsage.agentOf("grok-cli")).isEqualTo(AgentUsage.Agent.GROK);
        assertThat(AgentUsage.agentOf("github-copilot-cli")).isEqualTo(AgentUsage.Agent.COPILOT);
        assertThat(AgentUsage.agentOf("Muse Code")).isEqualTo(AgentUsage.Agent.MUSE);
        assertThat(AgentUsage.agentOf("antigravity")).isEqualTo(AgentUsage.Agent.ANTIGRAVITY);
        assertThat(AgentUsage.agentOf("agy")).isEqualTo(AgentUsage.Agent.ANTIGRAVITY);
        assertThat(AgentUsage.agentOf("cursor-agent")).isNull();
    }

    @Test
    void a_model_id_is_kept_only_in_a_model_ids_shape() {
        assertThat(AgentTokens.model("claude-opus-5-5")).isEqualTo("claude-opus-5-5");
        assertThat(AgentTokens.model("us.anthropic.claude-sonnet-5-5:0")).isEqualTo("us.anthropic.claude-sonnet-5-5:0");
        assertThat(AgentTokens.model("openai/gpt-5.1_codex")).isEqualTo("openai/gpt-5.1_codex");
        assertThat(AgentTokens.model("<synthetic>")).isEqualTo(AgentTokens.UNKNOWN);
        assertThat(AgentTokens.model("Claude Opus")).isEqualTo(AgentTokens.UNKNOWN);
        assertThat(AgentTokens.model("a".repeat(65))).isEqualTo(AgentTokens.UNKNOWN);
        assertThat(AgentTokens.model(42L)).isEqualTo(AgentTokens.UNKNOWN);
        assertThat(AgentTokens.model(null)).isEqualTo(AgentTokens.UNKNOWN);
    }
}
