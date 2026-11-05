// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.mcp.McpRevision;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
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
 * engine that opened it. A call whose id is found nowhere resolves to no connection, and its runs
 * journal as {@code mcp} with no session.
 *
 * <p>Bounded: a client that never sends {@code DELETE /mcp} leaves its entry behind, and the
 * oldest entries fall off past {@link #CAP} rather than growing with the engine's uptime; one that
 * fell off reloads from the store when its client returns.
 */
public final class McpConnections {

    static final int CAP = 256;

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

    private final SecureRandom random = new SecureRandom();
    private final LongSupplier clock;
    private volatile Store store = Store.NONE;
    private final Map<String, McpConnection> live = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, McpConnection> eldest) {
            return size() > CAP;
        }
    };

    /** When each loaded connection's {@code lastSeenAt} was last stored. */
    private final Map<String, Long> stored = new LinkedHashMap<>();

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

    /**
     * Mint a connection for a client that just sent {@code initialize}, pinned to {@code project}
     * when it is non-null. It is not stored until {@link #save} is called, once its session is open.
     */
    public synchronized McpConnection open(
            @Nullable String client, @Nullable String project, McpRevision revision, McpElicitModes elicitation) {
        String id;
        do {
            id = String.format("%0" + ID_HEX_DIGITS + "x", random.nextInt(1 << (4 * ID_HEX_DIGITS)));
        } while (live.containsKey(id) || store.holds(id));
        McpConnection c = new McpConnection(id, client, project, revision, elicitation, clock.getAsLong());
        load(c);
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
        }
        if (c == null) {
            McpConnection found = store.load(key);
            if (found == null) return null;
            synchronized (this) {
                c = live.get(key);
                if (c == null) {
                    c = found;
                    load(c);
                    c.onChange(() -> store.save(found));
                    stored.put(key, found.lastSeenAt());
                }
            }
        }
        long now = clock.getAsLong();
        c.seen(now);
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

    public synchronized int size() {
        return live.size();
    }

    private void load(McpConnection c) {
        live.put(c.id(), c);
        stored.keySet().retainAll(live.keySet());
    }
}
