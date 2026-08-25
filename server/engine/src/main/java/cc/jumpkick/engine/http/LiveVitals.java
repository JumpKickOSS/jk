// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Change-gated live vitals on the dashboard SSE bus ({@link HttpEvents}).
 *
 * <p><strong>Sampled</strong> host/engine status (~2s) and optional cache (slow) run only while
 * {@link HttpEvents#hasDashboardSubscribers()} is true — an MCP progress stream alone neither
 * starts nor sustains the samplers, and chrome frames go to dashboard subscriptions only
 *. A tiny last-published fingerprint suppresses no-op frames (e.g. free RAM still
 * presents as the same MiB). This is <em>not</em> a server-side UI model — only the last telegram
 * we put on the wire (~tens of bytes).
 *
 * <p><strong>Inflicted</strong> build progress stays on the engine's direct {@code publish} path;
 * never batched through this sampler.
 */
public final class LiveVitals implements AutoCloseable {

    /** Host / engine vitals cadence while any SSE client is attached. */
    static final long STATUS_PERIOD_MILLIS = 2_000;

    /**
     * Artifact/action storage walk is IO-shaped and allocates heavily on large stores. While
     * dashboard subscribers exist we refresh on this period as a safety net; post-build
     * {@link #nudgeCache()} and Status {@code GET /api/cache} are preferred for freshness.
     * Initial delay matches the period so engine-start + Chrome reconnect does not walk
     * immediately (see hydrateFor).
     */
    static final long CACHE_PERIOD_MILLIS = 60_000;

    private final HttpEvents events;
    private final Supplier<StatusSnapshot> status;
    private final Supplier<CacheSnapshot> cache;

    /** Presentation-quantized last status put on the wire; null until first successful publish. */
    private final AtomicReference<PresentStatus> lastStatus = new AtomicReference<>();

    /** Last published dual-surface cache totals (MiB quanta); null until first publish. */
    private final AtomicReference<PresentCache> lastCache = new AtomicReference<>();

    /** Last captured full snapshot — serves connect hydrate without a fresh store walk. */
    private final AtomicReference<CacheSnapshot> lastCacheSnapshot = new AtomicReference<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jk-live-vitals");
        t.setDaemon(true);
        return t;
    });

    private final Object scheduleLock = new Object();
    private ScheduledFuture<?> statusTask;
    private ScheduledFuture<?> cacheTask;

    public LiveVitals(HttpEvents events, Supplier<StatusSnapshot> status, Supplier<CacheSnapshot> cache) {
        this.events = Objects.requireNonNull(events, "events");
        this.status = Objects.requireNonNull(status, "status");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    /** Start samplers if needed (idempotent). Call when an SSE stream attaches. */
    public void onSubscriberJoined() {
        synchronized (scheduleLock) {
            if (statusTask == null || statusTask.isCancelled()) {
                statusTask = scheduler.scheduleAtFixedRate(
                        this::tickStatusSafe, STATUS_PERIOD_MILLIS, STATUS_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
            }
            if (cacheTask == null || cacheTask.isCancelled()) {
                cacheTask = scheduler.scheduleAtFixedRate(
                        this::tickCacheSafe, CACHE_PERIOD_MILLIS, CACHE_PERIOD_MILLIS, TimeUnit.MILLISECONDS);
            }
        }
    }

    /** Stop samplers when the last dashboard subscriber leaves (MCP streams don't count). */
    public void onSubscriberLeft() {
        if (events.hasDashboardSubscribers()) return;
        synchronized (scheduleLock) {
            if (!events.hasDashboardSubscribers()) {
                cancel(statusTask);
                cancel(cacheTask);
                statusTask = null;
                cacheTask = null;
            }
        }
    }

    /**
     * Force-publish current status (connect hydrate or plan edge). Always attempts a sample;
     * still change-gates unless {@code force}.
     */
    public void publishStatus(boolean force) {
        if (!force && !events.hasDashboardSubscribers()) return;
        try {
            StatusSnapshot s = status.get();
            if (s == null) return;
            PresentStatus present = PresentStatus.of(s);
            if (!force) {
                PresentStatus prev = lastStatus.get();
                if (present.equals(prev)) return;
            }
            lastStatus.set(present);
            // One serializer: StatusSnapshot.toJson() is the same object GET /api/status returns,
            // minus the REST-only httpUrl / config knobs it chains on — they do not change on a 2s
            // tick, so they never ride the live stream.
            events.publishDashboard("status", s.toJson());
        } catch (RuntimeException ignored) {
            // Sampler must never kill the schedule thread
        }
    }

    /**
     * Force-publish current cache/storage snapshot (connect hydrate or post-build). Change-gated on
     * dual-surface MiB totals unless {@code force}. Live frames use the <strong>thin</strong>
     * dual-surface payload; full section breakdown stays on {@code GET /api/cache}.
     */
    public void publishCache(boolean force) {
        if (!force && !events.hasDashboardSubscribers()) return;
        try {
            CacheSnapshot c = cache.get();
            if (c == null) return;
            lastCacheSnapshot.set(c);
            PresentCache present = PresentCache.of(c);
            if (!force) {
                PresentCache prev = lastCache.get();
                if (present.equals(prev)) return;
            }
            lastCache.set(present);
            events.publishDashboard("cache", c.toThinJson());
        } catch (RuntimeException ignored) {
            // disk walk failures are best-effort
        }
    }

    /**
     * Post-build nudge: run the change-gated cache publish on the sampler thread instead of the
     * caller's. The capture walks the store; it must never sit on a request-finish path where it
     * delays the journal write and the terminal frame.
     */
    public void nudgeCache() {
        if (!events.hasDashboardSubscribers()) return;
        try {
            scheduler.execute(() -> publishCache(false));
        } catch (RejectedExecutionException ignored) {
            // closing — nothing left to notify
        }
    }

    /**
     * Connect hydrate for one new subscription: current status plus the last captured cache
     * snapshot, delivered to <em>that subscription only</em> — existing tabs already hold these
     * facts, and re-broadcasting them duplicated chrome on every new tab. The cache side
     * never walks the disk on the connect path: it re-sends a stored snapshot when one
     * exists. It does <strong>not</strong> schedule a fresh walk — exclusive store walks allocate
     * tens of MiB and leave SerialGC committed heap expanded; first numbers come from
     * {@code GET /api/cache} (Status view), post-build {@link #nudgeCache()}, or the slow sampler.
     */
    public void hydrateFor(HttpEvents.Subscription sub) {
        try {
            StatusSnapshot s = status.get();
            if (s != null) {
                lastStatus.set(PresentStatus.of(s));
                events.deliverTo(sub, "status", s.toJson());
            }
        } catch (RuntimeException ignored) {
            // status sampling is best-effort on the connect path
        }
        CacheSnapshot last = lastCacheSnapshot.get();
        if (last != null) {
            lastCache.set(PresentCache.of(last));
            events.deliverTo(sub, "cache", last.toThinJson());
        }
        // No async publishCache here: engine-start reconnect storms must not walk multi-GiB stores.
    }

    private void tickStatusSafe() {
        if (!events.hasDashboardSubscribers()) {
            onSubscriberLeft();
            return;
        }
        publishStatus(false);
    }

    private void tickCacheSafe() {
        if (!events.hasDashboardSubscribers()) {
            onSubscriberLeft();
            return;
        }
        publishCache(false);
    }

    private static void cancel(ScheduledFuture<?> f) {
        if (f != null) f.cancel(false);
    }

    @Override
    public void close() {
        synchronized (scheduleLock) {
            cancel(statusTask);
            cancel(cacheTask);
            statusTask = null;
            cacheTask = null;
            if (!scheduler.isShutdown()) scheduler.shutdownNow();
        }
    }

    /**
     * Quantized status for equality — available/heap to 1 MiB, CPU load to 1 percentage point,
     * load average to 0.1, counters exact. Keeps "still 5.0 GiB available" from spamming the wire.
     */
    record PresentStatus(
            int activeBuildPlans,
            int activeRequests,
            int loadPp,
            int loadAvgTenths,
            long availableMib,
            long totalMib,
            long heapUsedMib,
            long heapCommittedMib,
            long rssMib,
            int cores,
            long pid,
            long aotTrainingPid,
            String engineEpoch) {

        static PresentStatus of(StatusSnapshot s) {
            int loadPp = s.systemCpuLoad() < 0 ? -1 : (int) Math.round(s.systemCpuLoad() * 100);
            int loadAvgTenths = s.systemLoadAverage() < 0 ? -1 : (int) Math.round(s.systemLoadAverage() * 10.0);
            return new PresentStatus(
                    s.activeBuildPlans(),
                    s.activeRequests(),
                    loadPp,
                    loadAvgTenths,
                    mib(s.availableMemoryBytes()),
                    mib(s.totalMemoryBytes()),
                    mib(s.heapUsedBytes()),
                    mib(s.heapCommittedBytes()),
                    mib(s.rssBytes()),
                    s.cores(),
                    s.pid(),
                    s.aotTrainingPid(),
                    s.engineEpoch() == null ? "" : s.engineEpoch());
        }

        private static long mib(long bytes) {
            if (bytes < 0) return -1;
            return bytes / (1024L * 1024L);
        }
    }

    /** Dual-surface cache fingerprint (1 MiB quanta on each surface + the cache budget). */
    record PresentCache(long actionMib, long artifactMib, long actionMaxMib) {
        static PresentCache of(CacheSnapshot c) {
            return new PresentCache(
                    c.actionCacheBytes() / (1024L * 1024L),
                    c.artifactStorageBytes() / (1024L * 1024L),
                    c.actionMaxBytes() / (1024L * 1024L));
        }
    }
}
