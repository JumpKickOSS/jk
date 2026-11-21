// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.mcp.McpRevision;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * One MCP client connection: the {@code Mcp-Session-Id} the engine minted at {@code initialize},
 * the short tag shown for it, the client name that handshake declared ({@code clientInfo.name}),
 * the protocol revision it negotiated, the elicitation modes it declared, and the project dir the
 * connection is bound to. {@link #label()} is what the journal records as the run's session —
 * {@code claude-code 3f9a} — so a supervisor can tell two agents on one project apart.
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
    private final String tag;
    private final @Nullable String client;
    private final boolean pinned;
    private final @Nullable String project;
    private final McpRevision revision;
    private final McpElicitModes elicitation;
    private final long createdAt;
    private volatile @Nullable String dir;
    private volatile long lastSeenAt;
    private volatile Runnable changed = () -> {};
    private volatile boolean closed;

    private final AtomicLong requests = new AtomicLong();
    private final AtomicInteger calls = new AtomicInteger();
    private final Map<String, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();

    /** A connection whose id is its tag too. */
    public McpConnection(String id, @Nullable String client) {
        this(id, client, null, McpRevision.LATEST, McpElicitModes.NONE);
    }

    /** A connection whose id is its tag too, pinned to {@code project} when it is non-null, opened now. */
    public McpConnection(
            String id,
            @Nullable String client,
            @Nullable String project,
            McpRevision revision,
            McpElicitModes elicitation) {
        this(id, id, client, project, revision, elicitation, Clock.SYSTEM.millis());
    }

    /**
     * A connection shown as {@code tag}, pinned to {@code project} when it is non-null, opened at
     * {@code createdAt}.
     */
    public McpConnection(
            String id,
            String tag,
            @Nullable String client,
            @Nullable String project,
            McpRevision revision,
            McpElicitModes elicitation,
            long createdAt) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("connection id is required");
        if (tag == null || tag.isBlank()) throw new IllegalArgumentException("connection tag is required");
        this.id = id;
        this.tag = tag;
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

    /** The short name {@link #label()} shows for this connection: the first hex digits of its id. */
    public String tag() {
        return tag;
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

    /** A request on this connection is being served; {@link #callEnded} follows it. */
    public void callStarted() {
        calls.incrementAndGet();
    }

    /** The request {@link #callStarted} announced has been answered. */
    public void callEnded() {
        calls.decrementAndGet();
    }

    /** Requests on this connection being served right now. */
    int outstanding() {
        return calls.get();
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
        m.put("tag", tag);
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

    /**
     * A connection read back from {@link #toMap}, or null when it carries no id. One stored before
     * connections had a tag was shown by its id, and still is.
     */
    public static @Nullable McpConnection of(Map<?, ?> m) {
        String id = MiniJson.str(m, "id");
        if (id == null || id.isBlank()) return null;
        String tag = MiniJson.str(m, "tag");
        if (tag == null || tag.isBlank()) tag = id;
        String revision = MiniJson.str(m, "revision");
        McpRevision rev = revision == null
                ? McpRevision.HTTP_DEFAULT
                : McpRevision.parse(revision).orElse(McpRevision.HTTP_DEFAULT);
        McpElicitModes modes = m.get("elicitation") instanceof Map<?, ?> e
                ? new McpElicitModes(Boolean.TRUE.equals(e.get("form")), Boolean.TRUE.equals(e.get("url")))
                : McpElicitModes.NONE;
        long created = m.get("createdAt") instanceof Number n ? n.longValue() : 0L;
        McpConnection c =
                new McpConnection(id, tag, MiniJson.str(m, "client"), MiniJson.str(m, "project"), rev, modes, created);
        c.dir = MiniJson.str(m, "dir");
        if (m.get("lastSeenAt") instanceof Number n) c.seen(n.longValue());
        return c;
    }

    /** {@code <client> <tag>}, or the bare tag when the client named itself nothing. */
    public String label() {
        return client == null ? tag : client + " " + tag;
    }

    /** A fresh id for a request the engine sends this client, and the response it will complete. */
    Expected expect() {
        String requestId = "jk-" + id + "-" + requests.incrementAndGet();
        CompletableFuture<Map<String, Object>> answer = new CompletableFuture<>();
        pending.put(requestId, answer);
        // Asked after close: no answer can come, so it fails as the ones close found did.
        if (closed) failPending();
        return new Expected(requestId, answer);
    }

    /** Stop waiting for {@code requestId}; a response that arrives later is dropped. */
    void forget(String requestId) {
        pending.remove(requestId);
    }

    /**
     * The connection is closed: every request still waiting on the client fails now, since no
     * answer can reach a connection that is gone.
     */
    void close() {
        closed = true;
        failPending();
    }

    private void failPending() {
        for (String requestId : List.copyOf(pending.keySet())) {
            CompletableFuture<Map<String, Object>> answer = pending.remove(requestId);
            if (answer != null) answer.completeExceptionally(new IllegalStateException("the session ended"));
        }
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
