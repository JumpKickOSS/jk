// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The live MCP connections, keyed by the {@code Mcp-Session-Id} each {@code initialize} minted.
 * Streamable HTTP is stateless per request, so the header is the only thing that ties two
 * {@code tools/call}s to one client; a call that carries no id, or one this engine never issued,
 * resolves to no connection and its runs journal as {@code mcp} with no session.
 *
 * <p>Bounded: a client that never sends {@code DELETE /mcp} leaves its entry behind, and the
 * oldest entries fall off past {@link #CAP} rather than growing with the engine's uptime.
 */
public final class McpConnections {

    static final int CAP = 256;

    /** Four hex digits: short enough to read aloud beside the client name, unique among live ids. */
    private static final int ID_HEX_DIGITS = 4;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, McpConnection> live = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, McpConnection> eldest) {
            return size() > CAP;
        }
    };

    /** Mint a connection for a client that just sent {@code initialize}. */
    public synchronized McpConnection open(@Nullable String client) {
        String id;
        do {
            id = String.format("%0" + ID_HEX_DIGITS + "x", random.nextInt(1 << (4 * ID_HEX_DIGITS)));
        } while (live.containsKey(id));
        McpConnection c = new McpConnection(id, client);
        live.put(id, c);
        return c;
    }

    /** The connection behind an {@code Mcp-Session-Id}, or null when absent or unknown. */
    public synchronized @Nullable McpConnection find(@Nullable String id) {
        return id == null || id.isBlank() ? null : live.get(id.trim());
    }

    /** {@code DELETE /mcp}: the client is done with this connection. */
    public synchronized boolean close(@Nullable String id) {
        return id != null && live.remove(id.trim()) != null;
    }

    public synchronized int size() {
        return live.size();
    }
}
