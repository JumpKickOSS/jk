// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.savings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
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
        return root.resolve("claude");
    }

    private Path codex() {
        return root.resolve("codex");
    }

    private AgentUsage usage(AtomicBoolean on) {
        return new AgentUsage(claude(), codex(), on::get);
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

        AgentTokens tokens = Objects.requireNonNull(usage().read("claude-code", PROJECT, T, T + 5_000));

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
    void claude_code_reads_only_the_checkout_of_the_episode() throws IOException {
        write(
                claude().resolve("projects")
                        .resolve(AgentUsage.claudeDir("/ws/other"))
                        .resolve("s.jsonl"),
                claudeLine(T + 1_000, "msg_x", "claude-opus-5-5", 1, 1, 1, 1));
        assertThat(usage().read("claude-code", PROJECT, T, T + 5_000)).isNull();
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

        AgentTokens tokens = Objects.requireNonNull(usage().read("codex-mcp-client", PROJECT, T, T + 5_000));

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
        assertThat(usage(on).read("claude-code", PROJECT, T, T + 5_000)).isNull();
        on.set(true);
        assertThat(usage(on).read("cursor", PROJECT, T, T + 5_000)).isNull();
        assertThat(usage(on).read(null, PROJECT, T, T + 5_000)).isNull();
        assertThat(usage(on).read("codex", PROJECT, T, T + 5_000)).isNull();
        assertThat(AgentUsage.NONE.read("claude-code", PROJECT, T, T + 5_000)).isNull();
    }

    @Test
    void a_file_last_written_before_the_episode_is_not_read_and_a_changed_one_is_read_again() throws IOException {
        Path file = claudeSession("s1.jsonl", claudeLine(T + 1_000, "msg_a", "claude-opus-5-5", 1, 0, 0, 1));
        Files.setLastModifiedTime(file, FileTime.fromMillis(T - 1));
        AgentUsage usage = usage();
        assertThat(usage.read("claude-code", PROJECT, T, T + 5_000)).isNull();

        Files.writeString(
                file, claudeLine(T + 2_000, "msg_b", "claude-opus-5-5", 2, 0, 0, 2) + "\n", StandardOpenOption.APPEND);
        Files.setLastModifiedTime(file, FileTime.fromMillis(T + 2_000));
        assertThat(Objects.requireNonNull(usage.read("claude-code", PROJECT, T, T + 5_000))
                        .sum()
                        .total())
                .isEqualTo(6);
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
