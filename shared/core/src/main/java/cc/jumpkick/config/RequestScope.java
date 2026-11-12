// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.host.KeyedLocks;
import cc.jumpkick.task.IoLedger;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import org.jspecify.annotations.Nullable;

/**
 * Derived facts that are true for the length of one request and meaningless after it.
 *
 * <p>Everything in that wanted a cache wanted <em>this</em> one, and each time the blocking
 * question was the same: when is it allowed to answer? A resolved workspace and a source listing are
 * stable while a build runs and stale the moment a {@code jk watch} iteration starts, so a
 * process-lifetime memo needs an invalidation story and a per-call memo is no memo at all.
 *
 * <p><strong>The answer is to scope to the request and never invalidate.</strong> A job's {@link
 * Request} already has an identity: its {@link IoLedger}, created once per job and shared by every
 * copy of the request and of the {@link Session}, because byte accounting has exactly the same
 * lifetime requirement. Keying on that instance means a scope cannot outlive the request that made
 * it, so there is nothing to invalidate and nothing to get wrong. A {@code jk watch} iteration is a
 * new request, so it gets a new scope for free.
 *
 * <p>The map holding scopes is <strong>weak-keyed</strong>: when a request's ledger becomes
 * unreachable its scope is collectable, so a long-lived engine does not accumulate one per build ever
 * run. That is the whole teardown story — no explicit close, nothing to forget to call, and no
 * lifecycle hook that a new verb could miss.
 *
 * <p><strong>Cache derived facts, never bytes.</strong> The engine runs a 256 MB SerialGC heap;
 * {@code PreflightMemo.fingerprintModule} streams rather than slurps for that reason, and
 * {@code details.jsonl} reaches 46.7 MB for one test run. A path list or a parsed manifest belongs
 * here. File contents do not. A fact whose size grows with the tree (a path list) is remembered
 * through {@link #get(Object, Function, ToLongFunction)}, which holds one request to {@link
 * #MAX_WEIGHED_BYTES}; an unweighed fact is constant-sized or charged to a budget of its own.
 *
 * <p><strong>What must not use this.</strong> Anything whose answer can change <em>during</em> a
 * request: build outputs under {@code target/}, live compiler output, CAS blob presence (another
 * process's prune can unlink one mid-build), and any negative result near a store — the rules
 * {@code ActionCache} and {@code ClasspathResolver} already document. This scope is for facts about
 * the <em>inputs</em> a request was launched against, which are fixed at launch by definition. The
 * one output-side fact kept here is a classes tree found whole against its compile record
 * ({@code ModuleOutputs.compileOutputsOnDisk}): a build never removes an output a hitting record
 * owns, so a yes holds for the request, and only the yes is kept. The other is a classes tree's
 * class-file stamps ({@code ClassStamps}), each taken with the count of steps ended so far and
 * trusted only while no other step has ended, since only a step writes a classes tree.
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

    /**
     * Bytes of weighed facts one request remembers. Past it a weighed fact is computed on every ask.
     * The scope lives inside its job's admission share, and this is part of the per-job base the
     * engine's memory admission charges every plan job.
     */
    public static final long MAX_WEIGHED_BYTES = 16L << 20;

    private final ConcurrentMap<Object, Object> facts = new ConcurrentHashMap<>();

    private final AtomicLong weighedBytes = new AtomicLong();

    /** The keys a {@link #getOnce} caller is computing now, each held while its fact is computed. */
    private final KeyedLocks<Object> computing = new KeyedLocks<>();

    private RequestScope() {}

    /**
     * The scope for the ambient request, or an unscoped one when there is no session installed (a
     * unit test, a CLI-side helper). An unscoped caller still gets a correct answer — it just
     * computes it every time.
     */
    public static RequestScope current() {
        if (ManifestOverlay.active()) return UNSCOPED;
        IoLedger ledger = ambientLedger();
        if (ledger == null) return UNSCOPED;
        return SCOPES.computeIfAbsent(ledger, l -> new RequestScope());
    }

    /** False for the scope of a caller with no request, which remembers nothing and computes every ask. */
    public boolean remembers() {
        return this != UNSCOPED;
    }

    /**
     * True when a job's request is bound, so {@link #current()} returns a scope that actually
     * caches. Callers whose side effects must be balanced by a per-request teardown (a charge that
     * a close releases) must not perform them when this is false — the unscoped scope has no
     * teardown.
     */
    public static boolean hasRequest() {
        return ambientLedger() != null;
    }

    /**
     * {@code compute}'s value for {@code key}, computed once per request. {@code compute} may ask
     * this scope for other facts: it runs outside the map, so a nested fact never re-enters the map
     * mid-update. Two threads racing on one key may both compute; the first value stored is the one
     * every caller gets. A fact too heavy for that race is asked through {@link #getOnce}.
     */
    @SuppressWarnings("unchecked")
    public <K, V> V get(K key, Function<K, V> compute) {
        if (this == UNSCOPED) return compute.apply(key);
        Object known = facts.get(key);
        if (known != null) return (V) known;
        V computed = compute.apply(key);
        // A null fact is not remembered, as computeIfAbsent would not remember it.
        Object prior = computed == null ? null : facts.putIfAbsent(key, computed);
        return prior != null ? (V) prior : computed;
    }

    /**
     * As {@link #get(Object, Function)} for a fact that costs {@code bytes} of heap: remembered only
     * while the scope's weighed facts stay within {@link #MAX_WEIGHED_BYTES}, else returned as
     * computed and computed again on the next ask.
     */
    @SuppressWarnings("unchecked")
    public <K, V> V get(K key, Function<K, V> compute, ToLongFunction<? super V> bytes) {
        if (this == UNSCOPED) return compute.apply(key);
        Object known = facts.get(key);
        if (known != null) return (V) known;
        V computed = compute.apply(key);
        if (computed == null) return computed;
        long cost = Math.max(0, bytes.applyAsLong(computed));
        if (!reserve(cost)) return computed;
        Object prior = facts.putIfAbsent(key, computed);
        if (prior == null) return computed;
        weighedBytes.addAndGet(-cost);
        return (V) prior;
    }

    /**
     * As {@link #get(Object, Function)} for a fact too heavy to compute twice: the first caller
     * computes while every other caller of the same key waits for its answer, where {@code get}
     * lets racing first callers each compute. A wide workspace's module list is one: every module a
     * fresh request plans asks for it at once, and each racing caller would parse every member.
     *
     * <p>The wait is a lock per key, held only while the fact is computed. A compute may ask this
     * scope for other facts, by {@code get} freely, and by {@code getOnce} only for a key of a lower
     * tier than its own: the workspace index reads the units, the units read the module list, and
     * the module list reads no heavy fact. Locks are therefore always taken down the tiers, never up,
     * so no two threads can each hold the lock the other waits for. A compute must not wait on
     * another thread that asks for its own key. The wait is a {@link ForkJoinPool#managedBlock}, so a
     * pool worker parked here does not starve the pool the computing caller may fan out to.
     */
    public <K, V> V getOnce(K key, Function<K, V> compute) {
        if (this == UNSCOPED) return compute.apply(key);
        @SuppressWarnings("unchecked")
        V known = (V) facts.get(key);
        if (known != null) return known;
        try (KeyedLocks.Held held = hold(key)) {
            return get(key, compute);
        }
    }

    /** As {@link #getOnce(Object, Function)} for a weighed fact ({@link #get(Object, Function, ToLongFunction)}). */
    public <K, V> V getOnce(K key, Function<K, V> compute, ToLongFunction<? super V> bytes) {
        if (this == UNSCOPED) return compute.apply(key);
        @SuppressWarnings("unchecked")
        V known = (V) facts.get(key);
        if (known != null) return known;
        try (KeyedLocks.Held held = hold(key)) {
            return get(key, compute, bytes);
        }
    }

    /** {@code key}'s compute lock, waited for as a blocker the pool can compensate for. */
    private KeyedLocks.Held hold(Object key) {
        var blocker = new ForkJoinPool.ManagedBlocker() {
            KeyedLocks.@Nullable Held held;

            @Override
            public boolean block() {
                held = computing.hold(key);
                return true;
            }

            @Override
            public boolean isReleasable() {
                return held != null;
            }
        };
        try {
            ForkJoinPool.managedBlock(blocker);
        } catch (InterruptedException e) {
            // block() takes the lock uninterruptibly, so this is never thrown; keep the flag if it is.
            Thread.currentThread().interrupt();
        }
        return Objects.requireNonNull(blocker.held, "compute lock");
    }

    private boolean reserve(long cost) {
        while (true) {
            long held = weighedBytes.get();
            if (held + cost > MAX_WEIGHED_BYTES) return false;
            if (weighedBytes.compareAndSet(held, held + cost)) return true;
        }
    }

    /** Bytes of weighed facts this scope remembers. Test seam. */
    public long weighedBytes() {
        return weighedBytes.get();
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
     * Ends the bound request's scope: its facts are gone before the next request begins. The weak
     * key alone cannot do this — a straggling pool task or a thread the job started still holds the
     * request, and with it the scope and every input-tree listing the job memoised.
     */
    public static void release() {
        IoLedger ledger = ambientLedger();
        if (ledger != null) release(ledger);
    }

    /**
     * Ends the scope of the request whose ledger is {@code ledger}, from any thread: where the job
     * ends, and for the job the engine gave up waiting on, whose runner may still be walking a
     * workspace long after the client has gone.
     */
    public static void release(IoLedger ledger) {
        SCOPES.remove(ledger);
    }

    /**
     * The ledger of the job's request bound now, or {@code null} when there is none.
     *
     * <p>Deliberately the bound {@link Request} and not {@code SessionContext.current().io()}. The
     * session is never null — off a request it is {@code Session.defaults()}, whose ledger is stable
     * for the life of the process — so keying on it would have turned this into a process-lifetime
     * cache with no invalidation, which is the exact thing the design exists to avoid.
     * {@code RequestScopeTest} caught that.
     *
     * <p>Only a job counts ({@link Request#isJob()}): the engine binds one in {@code JobEnvelope},
     * its per-job boundary, so "a request exists" has a single unambiguous definition, and a caller
     * outside one — a synchronous read, the CLI, a unit test, a bare thread — is uncached rather
     * than wrongly cached.
     */
    private static @Nullable IoLedger ambientLedger() {
        Request r = Request.current();
        return r != null && r.isJob() ? r.io() : null;
    }
}
