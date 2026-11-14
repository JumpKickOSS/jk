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
 * {@code GET /mcp?progressToken=…} stream can filter to one job. Past {@link #MAX_ENTRIES} the
 * oldest token whose job has ended goes first; a running job's token goes only when every token's
 * job is running.
 */
public final class ProgressTokenRegistry {

    static final int MAX_ENTRIES = 256;

    /** Insertion order, oldest first. Guarded by itself. */
    private final LinkedHashMap<String, Long> tokens = new LinkedHashMap<>();

    private volatile LongPredicate live = jid -> false;

    /** Whether a job is still running; the engine sets it from its live runs. */
    public void live(LongPredicate live) {
        this.live = Objects.requireNonNull(live);
    }

    /** Bind {@code progressToken} → {@code requestId} (overwrites if the token is reused). */
    public void bind(String progressToken, long requestId) {
        if (progressToken == null || progressToken.isBlank()) return;
        String key = canonicalText(progressToken);
        synchronized (tokens) {
            tokens.remove(key);
            if (tokens.size() >= MAX_ENTRIES) evictOne();
            tokens.put(key, requestId);
        }
    }

    private void evictOne() {
        LongPredicate running = live;
        Iterator<Map.Entry<String, Long>> it = tokens.entrySet().iterator();
        while (it.hasNext()) {
            if (!running.test(it.next().getValue())) {
                it.remove();
                return;
            }
        }
        Iterator<String> oldest = tokens.keySet().iterator();
        if (oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    /** Resolve a progress token to a request id, or {@code null} if unknown. */
    public @Nullable Long resolve(String progressToken) {
        if (progressToken == null || progressToken.isBlank()) return null;
        synchronized (tokens) {
            return tokens.get(canonicalText(progressToken));
        }
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
