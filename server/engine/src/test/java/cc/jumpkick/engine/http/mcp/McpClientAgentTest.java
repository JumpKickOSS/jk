// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.docs.AgentChoice;
import cc.jumpkick.docs.AgentSetup;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The agent an MCP client is, by the name its {@code initialize} declared. */
class McpClientAgentTest {

    @Test
    void the_names_clients_declare_map_to_their_agents() {
        assertThat(McpClientAgent.of("claude-code")).isEqualTo(AgentSetup.Host.CLAUDE);
        assertThat(McpClientAgent.of("Cursor")).isEqualTo(AgentSetup.Host.CURSOR);
        assertThat(McpClientAgent.of("cursor-vscode")).isEqualTo(AgentSetup.Host.CURSOR);
        assertThat(McpClientAgent.of("gemini-cli-mcp-client")).isEqualTo(AgentSetup.Host.GEMINI);
        assertThat(McpClientAgent.of("opencode")).isEqualTo(AgentSetup.Host.OPENCODE);
        assertThat(McpClientAgent.of("antigravity")).isEqualTo(AgentSetup.Host.AGY);
        assertThat(McpClientAgent.of("codex-mcp-client")).isEqualTo(AgentSetup.Host.CODEX);
    }

    @Test
    void an_unknown_or_missing_name_is_no_agent() {
        assertThat(McpClientAgent.of("Visual Studio Code")).isNull();
        assertThat(McpClientAgent.of("claude-ai")).isNull();
        assertThat(McpClientAgent.of(null)).isNull();
    }

    @Test
    void the_client_s_agent_joins_the_setting_unless_it_wires_none() {
        McpConnection claude = new McpConnection("c1", "claude-code");
        AgentChoice detect = new AgentChoice(AgentSetup.Mode.DETECT, Set.of(AgentSetup.Host.CODEX));
        AgentChoice none = new AgentChoice(AgentSetup.Mode.NONE, Set.of());

        assertThat(McpClientAgent.choice(claude, detect))
                .isEqualTo(
                        new AgentChoice(AgentSetup.Mode.DETECT, Set.of(AgentSetup.Host.CLAUDE, AgentSetup.Host.CODEX)));
        assertThat(McpClientAgent.choice(claude, none).mode()).isEqualTo(AgentSetup.Mode.NONE);
        assertThat(McpClientAgent.choice(new McpConnection("c2", "fake"), detect))
                .isEqualTo(detect);
        assertThat(McpClientAgent.choice(null, detect)).isEqualTo(detect);
    }
}
