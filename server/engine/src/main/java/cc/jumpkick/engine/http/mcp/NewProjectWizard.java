// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.docs.AgentChoice;
import cc.jumpkick.form.Values;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.time.Clock;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The dashboard's new-project wizard as a place to send a human: links that open it pre-filled, and
 * the creates that answer an MCP call waiting on one. A link that carries an {@code elicitation} id
 * ties the wizard's create back to the call that opened it, and carries that call's agent choice to
 * the create, so a project made through the hand-off is wired as the call itself would wire it.
 *
 * <p>The call waits {@link #waiting()} for the create; the link outlives it until its deadline,
 * {@link #LIFETIME} after it opened, so a create that lands after the call gave up is still wired
 * for the call's agents, and {@link #complete} says no call took it. A create spends its link. At
 * most {@link #MAX_OPEN} links are kept; past that the oldest goes.
 *
 * <p>Link shape: {@code <base>#new?<key>=<value>&…&elicitation=<id>&t=<token>}, one pair per
 * answer, a {@code multi} joined on commas. The SPA stores and scrubs {@code t}.
 */
public final class NewProjectWizard {

    /** The query key naming the elicitation a link answers. */
    public static final String ELICITATION = "elicitation";

    /** How long a link's agent choice is kept after it opened, whether or not a call still waits. */
    static final Duration LIFETIME = Duration.ofHours(1);

    /** The most links kept at once. */
    static final int MAX_OPEN = 64;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Supplier<@Nullable String> base;
    private final Supplier<@Nullable String> token;
    private final Duration wait;
    private final Clock clock;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /**
     * An open link: the create a call waits on (cancelled once the call stops waiting), the agents
     * it wires for, when the link stops carrying them, and whether a create spent it.
     */
    private record Pending(
            CompletableFuture<Map<String, Object>> created, AgentChoice agents, long deadline, AtomicBoolean spent) {

        /** Whether the deadline, a {@link Clock#nanos} reading, has passed at {@code now}. */
        boolean expired(long now) {
            return now - deadline >= 0;
        }
    }

    /**
     * @param base the dashboard's base URL ({@code http://127.0.0.1:8910/}), or null while HTTP is
     *     not serving
     * @param token the bearer token a link carries, or null when there is none
     */
    public NewProjectWizard(Supplier<@Nullable String> base, Supplier<@Nullable String> token) {
        this(base, token, McpNewForm.WAIT, Clock.SYSTEM);
    }

    /** As above, with a call waiting {@code wait} for a create and links aging by {@code clock}. */
    public NewProjectWizard(
            Supplier<@Nullable String> base, Supplier<@Nullable String> token, Duration wait, Clock clock) {
        this.base = base;
        this.token = token;
        this.wait = wait;
        this.clock = clock;
    }

    /** How long a call waits for the create that answers its link. */
    public Duration waiting() {
        return wait;
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
     * the choice of the call that opened it, until {@link #LIFETIME} from now.
     */
    synchronized String open(AgentChoice agents) {
        long now = clock.nanos();
        expire(now);
        while (pending.size() >= MAX_OPEN) {
            pending.entrySet().stream()
                    .min(Comparator.comparingLong(e -> e.getValue().deadline() - now))
                    .ifPresent(e -> pending.remove(e.getKey()));
        }
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        String id = Hashing.hex(bytes);
        pending.put(id, new Pending(new CompletableFuture<>(), agents, now + LIFETIME.toNanos(), new AtomicBoolean()));
        return id;
    }

    /** What the wizard created for {@code id}; completes when its create lands. */
    CompletableFuture<Map<String, Object>> created(String id) {
        Pending p = live(id);
        return p != null
                ? p.created()
                : CompletableFuture.failedFuture(new IllegalStateException("no elicitation " + id));
    }

    /**
     * The agents a create for {@code id} wires: those of the call that opened it, while its link
     * lives, even after the call stopped waiting; null once it is spent or past its deadline, so
     * the create wires as an ordinary one.
     */
    public @Nullable AgentChoice agents(String id) {
        Pending p = live(id);
        return p == null || p.spent().get() ? null : p.agents();
    }

    /** The call stops waiting for {@code id}; a later create still gets its agents until the deadline. */
    void close(String id) {
        Pending p = pending.get(id);
        if (p == null) return;
        p.created().cancel(false);
        if (p.spent().get()) pending.remove(id, p);
    }

    /**
     * The wizard created {@code project} ({@code path}, {@code projectId}) for {@code id}, which
     * spends its link; false when no call was still waiting on it, so none was told.
     */
    public boolean complete(String id, Map<String, Object> project) {
        Pending p = live(id);
        if (p == null || !p.spent().compareAndSet(false, true)) return false;
        boolean taken = p.created().complete(Map.copyOf(project));
        // A call still waiting drops the link when it stops; with none, nothing will.
        if (!taken) pending.remove(id, p);
        return taken;
    }

    /** {@code id}'s link, or null when there is none or its deadline passed. */
    private @Nullable Pending live(String id) {
        expire(clock.nanos());
        return pending.get(id);
    }

    /** Drops the links whose deadline passed; their calls stopped waiting long before. */
    private void expire(long now) {
        pending.values().removeIf(p -> p.expired(now));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
