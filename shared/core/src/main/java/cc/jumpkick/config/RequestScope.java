// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.task.IoLedger;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Derived facts that are true for the length of one request and meaningless after it.
 *
 * <p>Everything in that wanted a cache wanted <em>this</em> one, and each time the blocking
 * question was the same: when is it allowed to answer? A resolved workspace and a source listing are
 * stable while a build runs and stale the moment a {@code jk watch} iteration starts, so a
 * process-lifetime memo needs an invalidation story and a per-call memo is no memo at all.
 *
 * <p><strong>The answer is to scope to the request and never invalidate.</strong> A request already
 * has an identity: {@link IoLedger} is created once per invocation and shared by every copy of the
 * {@link Session} — its own javadoc says so, because byte accounting has exactly the same lifetime
 * requirement. Keying on that instance means a scope cannot outlive the request that made it, so
 * there is nothing to invalidate and nothing to get wrong. A {@code jk watch} iteration is a new
 * request, so it gets a new scope for free.
 *
 * <p>The map holding scopes is <strong>weak-keyed</strong>: when a request's ledger becomes
 * unreachable its scope is collectable, so a long-lived engine does not accumulate one per build ever
 * run. That is the whole teardown story — no explicit close, nothing to forget to call, and no
 * lifecycle hook that a new verb could miss.
 *
 * <p><strong>Cache derived facts, never bytes.</strong> The engine runs a 256 MB SerialGC heap;
 * {@code PreflightMemo.fingerprintModule} streams rather than slurps for that reason, and
 * {@code details.jsonl} reaches 46.7 MB for one test run. A path list or a parsed manifest belongs
 * here. File contents do not.
 *
 * <p><strong>What must not use this.</strong> Anything whose answer can change <em>during</em> a
 * request: build outputs under {@code target/}, live compiler output, CAS blob presence (another
 * process's prune can unlink one mid-build), and any negative result near a store — the rules
 * {@code ActionCache} and {@code ClasspathResolver} already document. This scope is for facts about
 * the <em>inputs</em> a request was launched against, which are fixed at launch by definition.
 */
public final class RequestScope {

    /**
     * Weak by key so a finished request's derived facts become collectable with its ledger, and
     * synchronized rather than concurrent because {@link WeakHashMap} is not thread-safe and this map
     * is touched once per scope lookup, not once per cached fact.
     */
    private static final Map<IoLedger, RequestScope> SCOPES = Collections.synchronizedMap(new WeakHashMap<>());

    /** A scope for callers with no request — every lookup misses, which is the honest answer. */
    private static final RequestScope UNSCOPED = new RequestScope();

    private final ConcurrentMap<Object, Object> facts = new ConcurrentHashMap<>();

    private RequestScope() {}

    /**
     * The scope for the ambient request, or an unscoped one when there is no session installed (a
     * unit test, a CLI-side helper). An unscoped caller still gets a correct answer — it just
     * computes it every time.
     */
    public static RequestScope current() {
        IoLedger ledger = ambientLedger();
        if (ledger == null) return UNSCOPED;
        return SCOPES.computeIfAbsent(ledger, l -> new RequestScope());
    }

    /**
     * True when an ambient request exists, so {@link #current()} returns a scope that actually
     * caches. Callers whose side effects must be balanced by a per-request teardown (a charge that
     * a close releases) must not perform them when this is false — the unscoped scope has no
     * teardown.
     */
    public static boolean hasRequest() {
        return ambientLedger() != null;
    }

    /** {@code compute}'s value for {@code key}, computed once per request. */
    @SuppressWarnings("unchecked")
    public <K, V> V get(K key, Function<K, V> compute) {
        if (this == UNSCOPED) return compute.apply(key);
        return (V) facts.computeIfAbsent(key, k -> compute.apply((K) k));
    }

    /** How many facts this scope holds. Test seam. */
    public int size() {
        return facts.size();
    }

    /** Test seam: drop every scope, so the next lookup recomputes. */
    public static void clearAll() {
        SCOPES.clear();
    }

    /**
     * Ends the ambient request's scope: its facts are gone before the next request begins. The weak
     * key alone cannot do this — the ledger is an inheritable thread-local, so every pooled thread
     * born or borrowed during the job still holds it, and with it the scope and every input-tree
     * listing the job memoised. Called once, where the ledger is closed.
     */
    public static void release() {
        IoLedger ledger = ambientLedger();
        if (ledger != null) SCOPES.remove(ledger);
    }

    /**
     * The ledger the current request opened, or {@code null} when there is no request.
     *
     * <p>Deliberately {@link IoLedger#ambient()} and not {@code SessionContext.current().io()}. The
     * session is never null — off a request it is {@code Session.defaults()}, whose ledger is stable
     * for the life of the process — so keying on it would have turned this into a process-lifetime
     * cache with no invalidation, which is the exact thing the design exists to avoid.
     * {@code RequestScopeTest} caught that.
     *
     * <p>{@code IoLedger.open} is called in one place, {@code JobEnvelope}, which is the engine's
     * per-job boundary. So "a request exists" has a single unambiguous definition, and a caller
     * outside one — the CLI, a unit test, a pool thread that pre-dates the request and inherits
     * nothing — is uncached rather than wrongly cached.
     */
    private static @Nullable IoLedger ambientLedger() {
        return IoLedger.ambient();
    }
}
