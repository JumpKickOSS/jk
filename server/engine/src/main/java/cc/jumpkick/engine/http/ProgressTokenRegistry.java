// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps MCP {@code _meta.progressToken} values to engine HTTP job {@code requestId}s so a later
 * {@code GET /mcp?progressToken=…} stream can filter to one job.
 *
 * <p>Bounded by practical agent concurrency — entries are not aggressively GC'd (jobs finish
 * quickly; tokens are short-lived strings). A soft cap drops oldest on overflow.
 */
public final class ProgressTokenRegistry {

    static final int MAX_ENTRIES = 256;

    private final ConcurrentHashMap<String, Long> tokens = new ConcurrentHashMap<>();

    /** Bind {@code progressToken} → {@code requestId} (overwrites if the token is reused). */
    public void bind(String progressToken, long requestId) {
        if (progressToken == null || progressToken.isBlank()) return;
        if (tokens.size() >= MAX_ENTRIES) {
            // Drop an arbitrary key — rare for agents to hold this many concurrent tokens.
            var it = tokens.keySet().iterator();
            if (it.hasNext()) {
                tokens.remove(it.next());
            }
        }
        tokens.put(canonicalText(progressToken), requestId);
    }

    /** Resolve a progress token to a request id, or {@code null} if unknown. */
    public Long resolve(String progressToken) {
        if (progressToken == null || progressToken.isBlank()) return null;
        return tokens.get(canonicalText(progressToken));
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
        tokens.clear();
    }

    int size() {
        return tokens.size();
    }

    @Override
    public String toString() {
        return "ProgressTokenRegistry{size=" + tokens.size() + "}";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ProgressTokenRegistry r && Objects.equals(tokens, r.tokens);
    }

    @Override
    public int hashCode() {
        return tokens.hashCode();
    }
}
