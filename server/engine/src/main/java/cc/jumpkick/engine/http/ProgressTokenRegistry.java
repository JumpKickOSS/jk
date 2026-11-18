// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongPredicate;
import org.jspecify.annotations.Nullable;

/**
 * Maps MCP {@code _meta.progressToken} values to engine HTTP job {@code requestId}s so a later
 * {@code notifications/cancelled} or {@code GET /mcp?progressToken=…} stream finds the job. A token
 * is the client's own, so it is bound under the {@code Mcp-Session-Id} it came with: two sessions
 * that use the same token text each find their own job, and a request with no session id finds only
 * tokens bound with none. Past {@link #MAX_ENTRIES} the oldest token whose job has ended goes first;
 * when every token's job is running, the binding session's own oldest goes, and another session's
 * only when it has none.
 */
public final class ProgressTokenRegistry {

    static final int MAX_ENTRIES = 256;

    /** A token as one session sent it; {@code session} is {@code ""} for a request with no session id. */
    private record Key(String session, String token) {}

    /** Insertion order, oldest first. Guarded by itself. */
    private final LinkedHashMap<Key, Long> tokens = new LinkedHashMap<>();

    private volatile LongPredicate live = jid -> false;

    /** Whether a job is still running; the engine sets it from its live runs. */
    public void live(LongPredicate live) {
        this.live = Objects.requireNonNull(live);
    }

    /**
     * Bind {@code progressToken} from {@code session} (null for none) → {@code requestId}; overwrites
     * if that session reuses the token.
     */
    public void bind(@Nullable String session, String progressToken, long requestId) {
        if (progressToken == null || progressToken.isBlank()) return;
        Key key = key(session, progressToken);
        synchronized (tokens) {
            tokens.remove(key);
            if (tokens.size() >= MAX_ENTRIES) evictOne(key.session());
            tokens.put(key, requestId);
        }
    }

    private void evictOne(String session) {
        LongPredicate running = live;
        Iterator<Map.Entry<Key, Long>> it = tokens.entrySet().iterator();
        while (it.hasNext()) {
            if (!running.test(it.next().getValue())) {
                it.remove();
                return;
            }
        }
        Iterator<Key> own = tokens.keySet().iterator();
        while (own.hasNext()) {
            if (own.next().session().equals(session)) {
                own.remove();
                return;
            }
        }
        Iterator<Key> oldest = tokens.keySet().iterator();
        if (oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    /** The request id {@code session} (null for none) bound {@code progressToken} to, or {@code null}. */
    public @Nullable Long resolve(@Nullable String session, String progressToken) {
        if (progressToken == null || progressToken.isBlank()) return null;
        synchronized (tokens) {
            return tokens.get(key(session, progressToken));
        }
    }

    /** Drop every token {@code session} bound: its connection is closed. */
    public void forget(@Nullable String session) {
        if (session == null || session.isBlank()) return;
        String s = session.strip();
        synchronized (tokens) {
            tokens.keySet().removeIf(k -> k.session().equals(s));
        }
    }

    private static Key key(@Nullable String session, String token) {
        return new Key(session == null ? "" : session.strip(), canonicalText(token));
    }

    /**
     * Canonical text form of a token. MCP allows integer progress tokens; MiniJson parses every
     * JSON number as Double, so {@code 5} stringifies as {@code "5.0"} while the SSE query carries
     * {@code "5"}. Integral double forms drop the fraction; every other token passes through.
     */
    public static String canonicalText(String token) {
        String s = token.trim();
        if (!s.matches("-?\\d+\\.0+")) return s;
        return s.substring(0, s.indexOf('.'));
    }

    /** Test seam: clear all bindings. */
    void clear() {
        synchronized (tokens) {
            tokens.clear();
        }
    }

    int size() {
        synchronized (tokens) {
            return tokens.size();
        }
    }

    @Override
    public String toString() {
        return "ProgressTokenRegistry{size=" + size() + "}";
    }
}
