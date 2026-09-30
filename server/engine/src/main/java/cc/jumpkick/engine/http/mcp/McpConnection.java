// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import org.jspecify.annotations.Nullable;

/**
 * One MCP client connection: the {@code Mcp-Session-Id} the engine minted at {@code initialize},
 * the client name that handshake declared ({@code clientInfo.name}), and the project dir the
 * connection is bound to. {@link #label()} is what the journal records as the run's session —
 * {@code claude-code 3f9a} — so a supervisor can tell two agents on one project apart.
 *
 * <p>The bind is per connection, so two agents on one engine never clobber each other's default
 * dir. It is set by {@code bind}, or implicitly by the first call that carries {@code dir}
 * while the connection is unbound. A {@link #pinned() pinned} connection was opened for one
 * project ({@code jk mcp}, the {@code Jk-Project} header): its tools take no {@code dir}.
 */
public final class McpConnection {

    private final String id;
    private final @Nullable String client;
    private final boolean pinned;
    private volatile @Nullable String dir;

    public McpConnection(String id, @Nullable String client) {
        this(id, client, null);
    }

    /** A connection pinned to {@code project} when it is non-null. */
    public McpConnection(String id, @Nullable String client, @Nullable String project) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("connection id is required");
        this.id = id;
        this.client = client == null || client.isBlank() ? null : client.trim();
        this.pinned = project != null && !project.isBlank();
        this.dir = pinned ? project : null;
    }

    /** Opened for one project: {@code tools/list} drops {@code dir} and every call defaults to it. */
    public boolean pinned() {
        return pinned;
    }

    public String id() {
        return id;
    }

    public @Nullable String client() {
        return client;
    }

    /** The bound project dir (absolute), or null while this connection has not named one. */
    public @Nullable String dir() {
        return dir;
    }

    /** Bind this connection to {@code dir}; a blank dir unbinds it. */
    public void bind(@Nullable String dir) {
        this.dir = dir == null || dir.isBlank() ? null : dir;
    }

    /** {@code <client> <id>}, or the bare id when the client named itself nothing. */
    public String label() {
        return client == null ? id : client + " " + id;
    }
}
