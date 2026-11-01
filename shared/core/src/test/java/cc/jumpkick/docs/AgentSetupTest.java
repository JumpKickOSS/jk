// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.docs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.MiniJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentSetupTest {

    @Test
    void detect_without_an_agent_writes_only_the_guide(@TempDir Path dir) throws Exception {
        AgentSetup.install(dir, AgentSetup.Mode.DETECT, false);
        assertThat(dir.resolve("AGENTS.md")).isRegularFile();
        assertThat(dir.resolve(".mcp.json")).doesNotExist();
        assertThat(dir.resolve("CLAUDE.md")).doesNotExist();
    }

    @Test
    void a_running_claude_gets_its_files_and_a_second_run_changes_nothing(@TempDir Path dir) throws Exception {
        AgentSetup.install(dir, AgentSetup.Mode.DETECT, true);
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
        assertThat(AgentSetup.install(dir, AgentSetup.Mode.DETECT, true)).isEmpty();
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
        AgentSetup.install(dir, AgentSetup.Mode.DETECT, false);
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
        List<String> lines = AgentSetup.install(dir, AgentSetup.Mode.ALL, false);
        assertThat(file).hasContent("{ // mine\n}");
        assertThat(lines).anyMatch(l -> l.startsWith("left " + file + " alone"));
        assertThat(dir.resolve(".claude/settings.json")).isRegularFile();
    }

    @Test
    void claude_md_gets_the_import_once_and_keeps_its_text(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("CLAUDE.md"), "# Mine\n");
        AgentSetup.install(dir, AgentSetup.Mode.DETECT, false);
        AgentSetup.install(dir, AgentSetup.Mode.DETECT, false);
        assertThat(dir.resolve("CLAUDE.md")).hasContent("# Mine\n\n@AGENTS.md");
    }

    @Test
    void none_writes_only_the_guide(@TempDir Path dir) throws Exception {
        AgentSetup.install(dir, AgentSetup.Mode.NONE, true);
        assertThat(dir.resolve("AGENTS.md")).isRegularFile();
        assertThat(dir.resolve(".mcp.json")).doesNotExist();
    }

    private static List<String> strings(List<?> values) {
        return values.stream().map(String::valueOf).toList();
    }
}
