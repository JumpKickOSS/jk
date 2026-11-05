// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.mcp.McpRevision;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * One MCP client connection: the {@code Mcp-Session-Id} the engine minted at {@code initialize},
 * the client name that handshake declared ({@code clientInfo.name}), the protocol revision it
 * negotiated, the elicitation modes it declared, and the project dir the connection is bound to.
 * {@link #label()} is what the journal records as the run's session — {@code claude-code 3f9a} —
 * so a supervisor can tell two agents on one project apart.
 *
 * <p>The bind is per connection, so two agents on one engine never clobber each other's default
 * dir. It is set by {@code bind}, or implicitly by the first call that carries {@code dir}
 * while the connection is unbound. A {@link #pinned() pinned} connection was opened for one
 * project ({@code jk mcp}, the {@code Jk-Project} header): its tools take no {@code dir}.
 *
 * <p>The requests the engine has sent this client and not yet heard back on are kept here, by the
 * JSON-RPC id the engine gave them, until the client's response arrives in a later message.
 *
 * <p>{@link #toMap()} is the connection as its session stores it, and {@link #of} reads it back in
 * another engine; pending requests are not stored.
 */
public final class McpConnection {

    private final String id;
    private final @Nullable String client;
    private final boolean pinned;
    private final @Nullable String project;
    private final McpRevision revision;
    private final McpElicitModes elicitation;
    private final long createdAt;
    private volatile @Nullable String dir;
    private volatile long lastSeenAt;
    private volatile Runnable changed = () -> {};

    private final AtomicLong requests = new AtomicLong();
    private final Map<String, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();

    public McpConnection(String id, @Nullable String client) {
        this(id, client, null, McpRevision.LATEST, McpElicitModes.NONE);
    }

    /** A connection pinned to {@code project} when it is non-null, opened now. */
    public McpConnection(
            String id,
            @Nullable String client,
            @Nullable String project,
            McpRevision revision,
            McpElicitModes elicitation) {
        this(id, client, project, revision, elicitation, Clock.SYSTEM.millis());
    }

    /** A connection pinned to {@code project} when it is non-null, opened at {@code createdAt}. */
    public McpConnection(
            String id,
            @Nullable String client,
            @Nullable String project,
            McpRevision revision,
            McpElicitModes elicitation,
            long createdAt) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("connection id is required");
        this.id = id;
        this.client = client == null || client.isBlank() ? null : client.trim();
        this.pinned = project != null && !project.isBlank();
        this.project = pinned ? project : null;
        this.dir = this.project;
        this.revision = Objects.requireNonNull(revision);
        this.elicitation = Objects.requireNonNull(elicitation);
        this.createdAt = createdAt;
        this.lastSeenAt = createdAt;
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

    /** The protocol revision this connection's {@code initialize} negotiated. */
    public McpRevision revision() {
        return revision;
    }

    /** The elicitation modes the client declared, as far as {@link #revision()} carries them. */
    public McpElicitModes elicitation() {
        return elicitation;
    }

    /** The bound project dir (absolute), or null while this connection has not named one. */
    public @Nullable String dir() {
        return dir;
    }

    /** Bind this connection to {@code dir}; a blank dir unbinds it. */
    public void bind(@Nullable String dir) {
        String next = dir == null || dir.isBlank() ? null : dir;
        if (Objects.equals(next, this.dir)) return;
        this.dir = next;
        changed.run();
    }

    /** When {@code initialize} opened it, epoch millis. */
    public long createdAt() {
        return createdAt;
    }

    /** When a request last named it, epoch millis. */
    public long lastSeenAt() {
        return lastSeenAt;
    }

    /** A request named this connection at {@code now}. */
    void seen(long now) {
        if (now > lastSeenAt) lastSeenAt = now;
    }

    /** Run {@code changed} whenever the bind changes. */
    void onChange(Runnable changed) {
        this.changed = changed;
    }

    /** The connection as its session stores it. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", 1);
        m.put("id", id);
        if (client != null) m.put("client", client);
        if (project != null) m.put("project", project);
        String bound = dir;
        if (bound != null) m.put("dir", bound);
        m.put("revision", revision.wire());
        Map<String, Object> modes = new LinkedHashMap<>();
        modes.put("form", elicitation.form());
        modes.put("url", elicitation.url());
        m.put("elicitation", modes);
        m.put("createdAt", createdAt);
        m.put("lastSeenAt", lastSeenAt);
        return m;
    }

    /** A connection read back from {@link #toMap}, or null when it carries no id. */
    public static @Nullable McpConnection of(Map<?, ?> m) {
        String id = MiniJson.str(m, "id");
        if (id == null || id.isBlank()) return null;
        String revision = MiniJson.str(m, "revision");
        McpRevision rev = revision == null
                ? McpRevision.HTTP_DEFAULT
                : McpRevision.parse(revision).orElse(McpRevision.HTTP_DEFAULT);
        McpElicitModes modes = m.get("elicitation") instanceof Map<?, ?> e
                ? new McpElicitModes(Boolean.TRUE.equals(e.get("form")), Boolean.TRUE.equals(e.get("url")))
                : McpElicitModes.NONE;
        long created = m.get("createdAt") instanceof Number n ? n.longValue() : 0L;
        McpConnection c =
                new McpConnection(id, MiniJson.str(m, "client"), MiniJson.str(m, "project"), rev, modes, created);
        c.dir = MiniJson.str(m, "dir");
        if (m.get("lastSeenAt") instanceof Number n) c.seen(n.longValue());
        return c;
    }

    /** {@code <client> <id>}, or the bare id when the client named itself nothing. */
    public String label() {
        return client == null ? id : client + " " + id;
    }

    /** A fresh id for a request the engine sends this client, and the response it will complete. */
    Expected expect() {
        String requestId = "jk-" + id + "-" + requests.incrementAndGet();
        CompletableFuture<Map<String, Object>> answer = new CompletableFuture<>();
        pending.put(requestId, answer);
        return new Expected(requestId, answer);
    }

    /** Stop waiting for {@code requestId}; a response that arrives later is dropped. */
    void forget(String requestId) {
        pending.remove(requestId);
    }

    /**
     * A JSON-RPC response from the client: completes the request it answers. False when it answers
     * nothing this connection is waiting for.
     */
    public boolean answer(Map<String, Object> response) {
        Object rawId = response.get("id");
        if (rawId == null) return false;
        CompletableFuture<Map<String, Object>> answer = pending.remove(String.valueOf(rawId));
        return answer != null && answer.complete(response);
    }

    /** One request the engine has sent and is waiting on. */
    record Expected(String requestId, CompletableFuture<Map<String, Object>> answer) {}
}
