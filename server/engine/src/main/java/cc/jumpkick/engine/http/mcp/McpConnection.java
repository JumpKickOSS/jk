// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import org.jspecify.annotations.Nullable;

/**
 * One MCP client connection: the {@code Mcp-Session-Id} the engine minted at {@code initialize} and
 * the client name that handshake declared ({@code clientInfo.name}). {@link #label()} is what the
 * journal records as the run's session — {@code claude-code 3f9a} — so a supervisor can tell two
 * agents on one project apart.
 */
public record McpConnection(String id, @Nullable String client) {

    public McpConnection {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("connection id is required");
        client = client == null || client.isBlank() ? null : client.trim();
    }

    /** {@code <client> <id>}, or the bare id when the client named itself nothing. */
    public String label() {
        return client == null ? id : client + " " + id;
    }
}
