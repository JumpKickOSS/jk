// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.docs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.MiniJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentSetupTest {

    @Test
    void detect_without_an_agent_writes_only_the_guide(@TempDir Path dir) throws Exception {
        AgentSetup.install(dir, AgentSetup.Mode.DETECT, Set.of());
        assertThat(dir.resolve("AGENTS.md")).isRegularFile();
        assertThat(dir.resolve(".mcp.json")).doesNotExist();
        assertThat(dir.resolve("CLAUDE.md")).doesNotExist();
    }

    @Test
    void a_running_claude_gets_its_files_and_a_second_run_changes_nothing(@TempDir Path dir) throws Exception {
        AgentSetup.install(dir, AgentSetup.Mode.DETECT, Set.of(AgentSetup.Host.CLAUDE));
        assertThat(dir.resolve("CLAUDE.md")).hasContent("@AGENTS.md");
        assertThat(Files.readString(dir.resolve(".mcp.json")))
                .contains("\"jk\"")
                .contains("\"mcp\"");
        String settings = Files.readString(dir.resolve(".claude/settings.json"));
        assertThat(settings)
                .contains("mcp__jk")
                .contains("Bash(jk *)")
                .contains("enabledMcpjsonServers")
                .contains(AgentSetup.HOOK_COMMAND);
        assertThat(AgentSetup.install(dir, AgentSetup.Mode.DETECT, Set.of(AgentSetup.Host.CLAUDE)))
                .isEmpty();
        assertThat(Files.readString(dir.resolve(".claude/settings.json"))).isEqualTo(settings);
    }

    @Test
    void existing_settings_keep_every_other_key(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".claude"));
        Path file = dir.resolve(".claude/settings.json");
        Files.writeString(file, """
                {"model":"x","permissions":{"allow":["Bash(git *)"],"deny":["Read(.env)"]},
                 "hooks":{"PreToolUse":[{"matcher":"Edit","hooks":[{"type":"command","command":"fmt"}]}]}}
                """);
        AgentSetup.install(dir, AgentSetup.Mode.DETECT, Set.of());
        Object root = MiniJson.parse(Files.readString(file));
        assertThat(MiniJson.str(root, "model")).isEqualTo("x");
        Object permissions = MiniJson.get(root, "permissions");
        assertThat(strings(MiniJson.list(permissions, "allow")))
                .containsExactly("Bash(git *)", "mcp__jk", "Bash(jk *)");
        assertThat(strings(MiniJson.list(permissions, "deny"))).containsExactly("Read(.env)");
        assertThat(MiniJson.list(MiniJson.get(root, "hooks"), "PreToolUse")).hasSize(2);
    }

    @Test
    void a_file_that_is_not_strict_json_is_left_alone(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(".mcp.json");
        Files.writeString(file, "{ // mine\n}");
        List<String> lines = AgentSetup.install(dir, AgentSetup.Mode.ALL, Set.of());
        assertThat(file).hasContent("{ // mine\n}");
        assertThat(lines).anyMatch(l -> l.startsWith("left " + file + " alone"));
        assertThat(dir.resolve(".claude/settings.json")).isRegularFile();
    }

    @Test
    void claude_md_gets_the_import_once_and_keeps_its_text(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("CLAUDE.md"), "# Mine\n");
        AgentSetup.install(dir, AgentSetup.Mode.DETECT, Set.of());
        AgentSetup.install(dir, AgentSetup.Mode.DETECT, Set.of());
        assertThat(dir.resolve("CLAUDE.md")).hasContent("# Mine\n\n@AGENTS.md");
    }

    @Test
    void none_writes_only_the_guide(@TempDir Path dir) throws Exception {
        AgentSetup.install(dir, AgentSetup.Mode.NONE, Set.of(AgentSetup.Host.CLAUDE));
        assertThat(dir.resolve("AGENTS.md")).isRegularFile();
        assertThat(dir.resolve(".mcp.json")).doesNotExist();
    }

    private static List<String> strings(List<?> values) {
        return values.stream().map(String::valueOf).toList();
    }

    @Test
    void all_writes_each_agents_own_shape(@TempDir Path dir) throws Exception {
        AgentSetup.install(dir, AgentSetup.Mode.ALL, Set.of());
        Object cursor = MiniJson.parse(Files.readString(dir.resolve(".cursor/mcp.json")));
        assertThat(MiniJson.str(MiniJson.get(MiniJson.get(cursor, "mcpServers"), "jk"), "command"))
                .isEqualTo("jk");
        Object vscode = MiniJson.get(
                MiniJson.get(MiniJson.parse(Files.readString(dir.resolve(".vscode/mcp.json"))), "servers"), "jk");
        assertThat(MiniJson.str(vscode, "type")).isEqualTo("stdio");
        Object gemini = MiniJson.parse(Files.readString(dir.resolve(".gemini/settings.json")));
        assertThat(MiniJson.get(MiniJson.get(gemini, "mcpServers"), "jk")).isNotNull();
        assertThat(strings(MiniJson.list(MiniJson.get(gemini, "context"), "fileName")))
                .containsExactly("AGENTS.md", "GEMINI.md");
        Object opencode = MiniJson.parse(Files.readString(dir.resolve("opencode.json")));
        Object server = MiniJson.get(MiniJson.get(opencode, "mcp"), "jk");
        assertThat(MiniJson.str(server, "type")).isEqualTo("local");
        assertThat(strings(MiniJson.list(server, "command"))).containsExactly("jk", "mcp");
        assertThat(AgentSetup.install(dir, AgentSetup.Mode.ALL, Set.of())).allMatch(l -> l.startsWith("next: "));
    }

    @Test
    void detect_follows_each_agents_marker(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".cursor"));
        Files.createDirectories(dir.resolve(".vscode"));
        AgentSetup.install(dir, AgentSetup.Mode.DETECT, Set.of());
        assertThat(dir.resolve(".cursor/mcp.json")).isRegularFile();
        assertThat(dir.resolve(".vscode/mcp.json")).isRegularFile();
        assertThat(dir.resolve(".gemini/settings.json")).doesNotExist();
        assertThat(dir.resolve("opencode.json")).doesNotExist();
        assertThat(dir.resolve(".mcp.json")).doesNotExist();
    }

    @Test
    void gemini_keeps_the_context_files_it_already_reads(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".gemini"));
        Path file = dir.resolve(".gemini/settings.json");
        Files.writeString(file, "{\"context\":{\"fileName\":\"CONTEXT.md\"},\"theme\":\"dark\"}");
        AgentSetup.install(dir, AgentSetup.Mode.DETECT, Set.of());
        Object root = MiniJson.parse(Files.readString(file));
        assertThat(strings(MiniJson.list(MiniJson.get(root, "context"), "fileName")))
                .containsExactly("AGENTS.md", "CONTEXT.md");
        assertThat(MiniJson.str(root, "theme")).isEqualTo("dark");
    }

    @Test
    void remove_undoes_all_and_keeps_other_keys(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".vscode"));
        Path vscode = dir.resolve(".vscode/mcp.json");
        Files.writeString(vscode, "{\"servers\":{\"other\":{\"type\":\"stdio\",\"command\":\"x\"}}}");
        AgentSetup.install(dir, AgentSetup.Mode.ALL, Set.of());
        AgentSetup.remove(dir);
        Object servers = MiniJson.get(MiniJson.parse(Files.readString(vscode)), "servers");
        assertThat(MiniJson.get(servers, "other")).isNotNull();
        assertThat(MiniJson.get(servers, "jk")).isNull();
        assertThat(dir.resolve(".cursor/mcp.json")).doesNotExist();
        assertThat(dir.resolve("opencode.json")).doesNotExist();
        assertThat(dir.resolve(".mcp.json")).doesNotExist();
        assertThat(dir.resolve("AGENTS.md")).doesNotExist();
    }

    @Test
    void an_installed_agy_gets_its_measured_rule_and_remove_takes_it(@TempDir Path dir) throws Exception {
        AgentSetup.install(dir, AgentSetup.Mode.DETECT, Set.of(AgentSetup.Host.AGY));
        Path rule = dir.resolve(".agents/rules/jk.md");
        assertThat(Files.readString(rule))
                .startsWith("---\ntrigger: always_on\n---\n")
                .contains("rename");
        assertThat(dir.resolve(".mcp.json")).doesNotExist();
        AgentSetup.remove(dir);
        assertThat(rule).doesNotExist();
    }

    @Test
    void grok_and_muse_get_their_trust_step_printed(@TempDir Path dir) throws Exception {
        List<String> lines =
                AgentSetup.install(dir, AgentSetup.Mode.DETECT, Set.of(AgentSetup.Host.GROK, AgentSetup.Host.MUSE));
        assertThat(lines).anyMatch(l -> l.startsWith("next: grok")).anyMatch(l -> l.startsWith("next: muse"));
        assertThat(dir.resolve(".grok")).doesNotExist();
    }

    @Test
    void an_installed_cursor_gets_its_mcp_file_without_a_cursor_directory(@TempDir Path dir) throws Exception {
        AgentSetup.install(dir, AgentSetup.Mode.DETECT, Set.of(AgentSetup.Host.CURSOR));
        assertThat(dir.resolve(".cursor/mcp.json")).isRegularFile();
    }
}
