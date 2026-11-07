// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.docs.AgentChoice;
import cc.jumpkick.docs.AgentSetup;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The coding agent an MCP client is, from the name its {@code initialize} declared ({@code
 * clientInfo.name}), so MCP {@code new} wires the project for the agent that asked for it rather
 * than only for the environment the engine was started in.
 */
public final class McpClientAgent {

    /**
     * Name prefixes, compared lowercase with everything but letters and digits dropped: {@code
     * claude-code}, {@code Cursor} / {@code cursor-vscode}, {@code gemini-cli-mcp-client}, {@code
     * opencode}, {@code antigravity}, {@code codex-mcp-client}.
     */
    private static final Map<String, AgentSetup.Host> PREFIXES = Map.of(
            "claudecode", AgentSetup.Host.CLAUDE,
            "cursor", AgentSetup.Host.CURSOR,
            "gemini", AgentSetup.Host.GEMINI,
            "opencode", AgentSetup.Host.OPENCODE,
            "antigravity", AgentSetup.Host.AGY,
            "agy", AgentSetup.Host.AGY,
            "codex", AgentSetup.Host.CODEX);

    private McpClientAgent() {}

    /** The agent {@code clientName} names, or null for a client jk writes no agent files for. */
    public static AgentSetup.@Nullable Host of(@Nullable String clientName) {
        if (clientName == null) return null;
        String key = clientName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (var e : PREFIXES.entrySet()) {
            if (key.startsWith(e.getKey())) return e.getValue();
        }
        return null;
    }

    /**
     * {@code setting} with the agent of {@code connection}'s client added; an anonymous or unknown
     * client leaves it as it is, and so does {@code JK_AGENTS=none}.
     */
    public static AgentChoice choice(@Nullable McpConnection connection, AgentChoice setting) {
        AgentSetup.Host host = of(connection == null ? null : connection.client());
        return host == null ? setting : setting.with(host);
    }
}
