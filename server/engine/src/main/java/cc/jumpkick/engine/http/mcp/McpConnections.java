// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.mcp.McpRevision;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The loaded MCP connections, keyed by the {@code Mcp-Session-Id} each {@code initialize} minted.
 * Streamable HTTP is stateless per request, so the header is the only thing that ties two
 * {@code tools/call}s to one client.
 *
 * <p>A cache over the {@link Store}: each connection is stored with its session when it opens or
 * changes, and an id this table has not loaded is looked up there, so a connection outlives the
 * engine that opened it. An id found nowhere is gone: the transport answers it {@code 404}.
 *
 * <p>Bounded by {@link #max(int)} ({@code [mcp] max-sessions}). To load one more past it, the table
 * unloads the least recently used idle connection: a stored one with no request being served and
 * none for {@link #QUIET_MILLIS}. It reloads from the store, the same id and session, when its
 * client returns. A connection reloading is never refused, so the table may run over its bound
 * until connections go idle; a new one is refused ({@link Full}) only when every loaded connection
 * is active.
 */
public final class McpConnections {

    /** How long a connection with no request being served must be quiet before it may unload. */
    static final long QUIET_MILLIS = Duration.ofSeconds(30).toMillis();

    /** Four hex digits: short enough to read aloud beside the client name, unique among open ids. */
    private static final int ID_HEX_DIGITS = 4;

    /** How stale a stored {@code lastSeenAt} may get before a request rewrites it. */
    static final long SEEN_EVERY_MILLIS = Duration.ofMinutes(1).toMillis();

    /** Where connections are kept beyond this table: the sessions they belong to. */
    public interface Store {
        /** Keep {@code connection} as it now stands. */
        void save(McpConnection connection);

        /** The stored connection {@code id}, or null when there is none. */
        @Nullable
        McpConnection load(String id);

        /** True when {@code id} is stored, so a new connection must not take it. */
        boolean holds(String id);

        Store NONE = new Store() {
            @Override
            public void save(McpConnection connection) {}

            @Override
            public @Nullable McpConnection load(String id) {
                return null;
            }

            @Override
            public boolean holds(String id) {
                return false;
            }
        };
    }

    /**
     * A new connection refused: every loaded connection is active. {@link #retryAfterSeconds} is
     * when the first of them could go idle.
     */
    public static final class Full extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final long retryAfterSeconds;

        Full(int max, long retryAfterSeconds) {
            super("jk engine is at its MCP session limit: all " + max + " sessions are active. Close idle"
                    + " agents, or raise [mcp] max-sessions (JK_MCP_MAX_SESSIONS) and restart the engine");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final LongSupplier clock;
    private volatile Store store = Store.NONE;
    private volatile int max = JkHttpConfig.DEFAULT_MCP_MAX_SESSIONS;

    /** Least recently used first. */
    private final Map<String, McpConnection> live = new LinkedHashMap<>(64, 0.75f, true);

    /** When each loaded, stored connection's {@code lastSeenAt} was last stored. */
    private final Map<String, Long> stored = new HashMap<>();

    public McpConnections() {
        this(Clock.SYSTEM::millis);
    }

    McpConnections(LongSupplier clock) {
        this.clock = clock;
    }

    /** Keep connections in {@code store} as well as in this table. */
    public void store(Store store) {
        this.store = store != null ? store : Store.NONE;
    }

    /** Load at most {@code max} connections while any of them is idle. */
    public void max(int max) {
        if (max < 1) throw new IllegalArgumentException("max-sessions must be at least 1: " + max);
        this.max = max;
    }

    /**
     * Mint a connection for a client that just sent {@code initialize}, pinned to {@code project}
     * when it is non-null. It is not stored until {@link #save} is called, once its session is open.
     *
     * @throws Full when the table is full and every loaded connection is active
     */
    public McpConnection open(
            @Nullable String client, @Nullable String project, McpRevision revision, McpElicitModes elicitation) {
        List<McpConnection> unloaded = new ArrayList<>();
        McpConnection c;
        try {
            synchronized (this) {
                long now = clock.getAsLong();
                if (!makeRoom(now, unloaded)) throw new Full(max, retryAfterSeconds(now));
                String id;
                do {
                    id = String.format("%0" + ID_HEX_DIGITS + "x", random.nextInt(1 << (4 * ID_HEX_DIGITS)));
                } while (live.containsKey(id) || store.holds(id));
                c = new McpConnection(id, client, project, revision, elicitation, now);
                live.put(id, c);
            }
        } finally {
            unloaded.forEach(store::save);
        }
        return c;
    }

    /** Store {@code c} as it stands; it is stored again whenever its bind changes. */
    public void save(McpConnection c) {
        c.onChange(() -> store.save(c));
        synchronized (this) {
            stored.put(c.id(), c.lastSeenAt());
        }
        store.save(c);
    }

    /**
     * The connection behind an {@code Mcp-Session-Id}: a loaded one, else the stored one, loaded now.
     * Null when absent or found nowhere. Notes the request on it.
     */
    public @Nullable McpConnection find(@Nullable String id) {
        if (id == null || id.isBlank()) return null;
        String key = id.trim();
        McpConnection c;
        synchronized (this) {
            c = live.get(key);
            if (c != null) c.seen(clock.getAsLong());
        }
        if (c == null) {
            McpConnection found = store.load(key);
            if (found == null) return null;
            List<McpConnection> unloaded = new ArrayList<>();
            synchronized (this) {
                c = live.get(key);
                if (c == null) {
                    // A returning connection is never refused: the table runs over until one idles.
                    makeRoom(clock.getAsLong(), unloaded);
                    c = found;
                    live.put(key, c);
                    c.onChange(() -> store.save(found));
                    stored.put(key, found.lastSeenAt());
                }
                c.seen(clock.getAsLong());
            }
            unloaded.forEach(store::save);
        }
        long now = clock.getAsLong();
        boolean stale;
        synchronized (this) {
            Long at = stored.get(key);
            stale = at != null && now - at >= SEEN_EVERY_MILLIS;
            if (stale) stored.put(key, now);
        }
        if (stale) store.save(c);
        return c;
    }

    /** {@code DELETE /mcp}: the client is done with this connection. */
    public synchronized boolean close(@Nullable String id) {
        if (id == null) return false;
        stored.remove(id.trim());
        return live.remove(id.trim()) != null;
    }

    /** Connections loaded right now. */
    public synchronized int size() {
        return live.size();
    }

    /**
     * How close the table is to its bound: {@code max} ({@code [mcp] max-sessions}), the
     * connections {@code loaded}, and how many of them are {@code active}, which no new connection
     * may unload.
     */
    public record Counts(int max, int loaded, int active) {}

    /** The table's {@link Counts} right now. */
    public synchronized Counts counts() {
        long now = clock.getAsLong();
        int active = 0;
        for (McpConnection c : live.values()) if (!idle(c, now)) active++;
        return new Counts(max, live.size(), active);
    }

    /** True when {@code id} is loaded, not only stored. */
    synchronized boolean loaded(String id) {
        return live.containsKey(id);
    }

    /**
     * Unload least recently used idle connections until one more fits, adding each to {@code
     * unloaded} to be stored as it last stood. False when the table is still full.
     */
    private boolean makeRoom(long now, List<McpConnection> unloaded) {
        while (live.size() >= max) {
            McpConnection idle = null;
            for (McpConnection c : live.values()) {
                if (idle(c, now)) {
                    idle = c;
                    break;
                }
            }
            if (idle == null) return false;
            live.remove(idle.id());
            stored.remove(idle.id());
            unloaded.add(idle);
        }
        return true;
    }

    /** Stored, so it can reload; nothing being served; quiet for {@link #QUIET_MILLIS}. */
    private boolean idle(McpConnection c, long now) {
        return stored.containsKey(c.id()) && c.outstanding() == 0 && now - c.lastSeenAt() >= QUIET_MILLIS;
    }

    /** Seconds until the first stored connection with nothing being served goes quiet; at least one. */
    private long retryAfterSeconds(long now) {
        long soonest = QUIET_MILLIS;
        for (McpConnection c : live.values()) {
            if (stored.containsKey(c.id()) && c.outstanding() == 0) {
                soonest = Math.min(soonest, c.lastSeenAt() + QUIET_MILLIS - now);
            }
        }
        return Math.max(1, (soonest + 999) / 1000);
    }
}
