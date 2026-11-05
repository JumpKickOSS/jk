// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The MCP protocol revisions jk speaks, oldest first. {@code initialize} answers the revision the
 * client asked for when jk speaks it, else {@link #LATEST}; the client then decides whether it can
 * continue. What a session may use follows from the revision it negotiated.
 *
 * @see <a href="https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle#version-negotiation">Version negotiation</a>
 */
public enum McpRevision {
    V2024_11_05("2024-11-05"),
    V2025_03_26("2025-03-26"),
    V2025_06_18("2025-06-18"),
    V2025_11_25("2025-11-25");

    public static final McpRevision LATEST = V2025_11_25;

    /**
     * What an HTTP request that names no revision and rides on no known session speaks: the
     * Streamable HTTP transport's own default.
     */
    public static final McpRevision HTTP_DEFAULT = V2025_03_26;

    private final String wire;

    McpRevision(String wire) {
        this.wire = wire;
    }

    /** The {@code protocolVersion} string. */
    public String wire() {
        return wire;
    }

    /** The revision {@code wire} names, or empty when jk does not speak it. */
    public static Optional<McpRevision> parse(@Nullable String wire) {
        if (wire == null) return Optional.empty();
        String w = wire.strip();
        for (McpRevision r : values()) if (r.wire.equals(w)) return Optional.of(r);
        return Optional.empty();
    }

    /** The answer to an {@code initialize} that asked for {@code requested}. */
    public static McpRevision negotiate(@Nullable Object requested) {
        return parse(requested == null ? null : String.valueOf(requested)).orElse(LATEST);
    }

    /** JSON-RPC batches: removed from MCP in 2025-06-18. */
    public boolean batches() {
        return compareTo(V2025_06_18) < 0;
    }

    /** Form-mode {@code elicitation/create}, from 2025-06-18. */
    public boolean formElicitation() {
        return compareTo(V2025_06_18) >= 0;
    }

    /** URL-mode {@code elicitation/create} and the {@code mode} field, from 2025-11-25. */
    public boolean urlElicitation() {
        return compareTo(V2025_11_25) >= 0;
    }
}
