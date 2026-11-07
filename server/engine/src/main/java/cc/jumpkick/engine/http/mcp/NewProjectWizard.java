// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.docs.AgentChoice;
import cc.jumpkick.form.Values;
import cc.jumpkick.host.Hashing;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The dashboard's new-project wizard as a place to send a human: links that open it pre-filled, and
 * the creates that answer an MCP call waiting on one. A link that carries an {@code elicitation} id
 * ties the wizard's create back to the call that opened it, and carries that call's agent choice to
 * the create, so a project made through the hand-off is wired as the call itself would wire it.
 *
 * <p>Link shape: {@code <base>#new?<key>=<value>&…&elicitation=<id>&t=<token>}, one pair per
 * answer, a {@code multi} joined on commas. The SPA stores and scrubs {@code t}.
 */
public final class NewProjectWizard {

    /** The query key naming the elicitation a link answers. */
    public static final String ELICITATION = "elicitation";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Supplier<@Nullable String> base;
    private final Supplier<@Nullable String> token;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /** A call waiting on the wizard: the create that will answer it, and the agents it wires for. */
    private record Pending(CompletableFuture<Map<String, Object>> created, AgentChoice agents) {}

    /**
     * @param base the dashboard's base URL ({@code http://127.0.0.1:8910/}), or null while HTTP is
     *     not serving
     * @param token the bearer token a link carries, or null when there is none
     */
    public NewProjectWizard(Supplier<@Nullable String> base, Supplier<@Nullable String> token) {
        this.base = base;
        this.token = token;
    }

    /**
     * The wizard pre-filled with {@code answers}, answering {@code elicitation} when not null; null
     * while the dashboard is not serving.
     */
    public @Nullable String link(Map<String, ?> answers, @Nullable String elicitation) {
        String root = base.get();
        if (root == null || root.isBlank()) return null;
        StringBuilder out = new StringBuilder(root.endsWith("/") ? root : root + "/").append("#new");
        char sep = '?';
        for (var e : answers.entrySet()) {
            if (e.getValue() == null) continue;
            out.append(sep).append(enc(e.getKey())).append('=').append(enc(Values.text(e.getValue())));
            sep = '&';
        }
        if (elicitation != null) {
            out.append(sep).append(ELICITATION).append('=').append(enc(elicitation));
            sep = '&';
        }
        String t = token.get();
        if (t != null && !t.isBlank()) out.append(sep).append("t=").append(t.strip());
        return out.toString();
    }

    /**
     * A new elicitation id, whose create {@link #created} completes and wires for {@code agents},
     * the choice of the call that opened it.
     */
    String open(AgentChoice agents) {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        String id = Hashing.hex(bytes);
        pending.put(id, new Pending(new CompletableFuture<>(), agents));
        return id;
    }

    /** What the wizard created for {@code id}; completes when its create lands. */
    CompletableFuture<Map<String, Object>> created(String id) {
        Pending p = pending.get(id);
        return p != null
                ? p.created()
                : CompletableFuture.failedFuture(new IllegalStateException("no elicitation " + id));
    }

    /**
     * The agents a create for {@code id} wires: those of the call waiting on it; null when no call
     * is, so the create wires as an ordinary one.
     */
    public @Nullable AgentChoice agents(String id) {
        Pending p = pending.get(id);
        return p == null ? null : p.agents();
    }

    /** Stops waiting for {@code id}; a later create with it is an ordinary create. */
    void close(String id) {
        pending.remove(id);
    }

    /**
     * The wizard created {@code project} ({@code path}, {@code projectId}) for {@code id}; false when
     * no call is waiting on it.
     */
    public boolean complete(String id, Map<String, Object> project) {
        Pending p = pending.get(id);
        return p != null && p.created().complete(Map.copyOf(project));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
