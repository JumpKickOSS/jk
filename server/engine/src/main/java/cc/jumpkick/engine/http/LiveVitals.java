// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import java.util.Objects;
import java.util.concurrent.Executors;
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
 * (JK-1512). A tiny last-published fingerprint suppresses no-op frames (e.g. free RAM still
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
     * Artifact/action storage walk is IO-shaped; only as a safety net while subscribers exist.
     * Inflicted publishes (after builds) are preferred when wired.
     */
    static final long CACHE_PERIOD_MILLIS = 30_000;

    private final HttpEvents events;
    private final Supplier<StatusSnapshot> status;
    private final Supplier<CacheSnapshot> cache;

    /** Presentation-quantized last status put on the wire; null until first successful publish. */
    private final AtomicReference<PresentStatus> lastStatus = new AtomicReference<>();

    /** Last published dual-surface cache totals (MiB quanta); null until first publish. */
    private final AtomicReference<PresentCache> lastCache = new AtomicReference<>();

    /** Last captured full snapshot — serves connect hydrate without a fresh store walk (JK-1513). */
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
     * Force-publish current status (connect hydrate or pipeline edge). Always attempts a sample;
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
            // SSE status payload matches GET /api/status core vitals (see StatusSnapshot fields).
            // httpUrl / config knobs stay REST-only — they do not change on a 2s tick.
            events.publishDashboard("status", statusJson(s));
        } catch (RuntimeException ignored) {
            // Sampler must never kill the schedule thread
        }
    }

    /**
     * Force-publish current cache/storage snapshot (connect hydrate or post-build). Change-gated on
     * dual-surface MiB totals unless {@code force}. Live frames use the <strong>thin</strong>
     * dual-surface payload (JK-1502); full section breakdown stays on {@code GET /api/cache}.
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
     * delays the journal write and the terminal frame (JK-1513).
     */
    public void nudgeCache() {
        if (!events.hasDashboardSubscribers()) return;
        try {
            scheduler.execute(() -> publishCache(false));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // closing — nothing left to notify
        }
    }

    /**
     * Connect hydrate: re-send the last captured snapshot immediately (no disk walk on the
     * connect path), then refresh async on the sampler thread so a stale snapshot self-corrects.
     * The first-ever connect has no snapshot yet — the async capture publishes shortly after, and
     * the SPA's REST hydrate covers the gap (JK-1513).
     */
    public void hydrateCache() {
        CacheSnapshot last = lastCacheSnapshot.get();
        if (last != null) {
            lastCache.set(PresentCache.of(last));
            events.publishDashboard("cache", last.toThinJson());
        }
        try {
            scheduler.execute(() -> publishCache(last == null));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // closing — nothing left to notify
        }
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

    /** Core vitals JSON (shared shape with a subset of {@code GET /api/status}). */
    static JsonOut statusJson(StatusSnapshot s) {
        return JsonOut.object()
                .put("version", s.version())
                .put("pid", s.pid())
                .put("startedAt", s.startedAtMillis())
                .put(
                        "uptimeSeconds",
                        Math.max(0, (System.currentTimeMillis() - s.startedAtMillis()) / 1000))
                .put("activeRequests", s.activeRequests())
                .put("activePipelines", s.activePipelines())
                .put("peakActiveRequests", s.peakActiveRequests())
                .put("peakActivePipelines", s.peakActivePipelines())
                .put("heapUsedBytes", s.heapUsedBytes())
                .put("heapCommittedBytes", s.heapCommittedBytes())
                .put("heapMaxBytes", s.heapMaxBytes())
                .put("rssBytes", s.rssBytes())
                .put("aotTrainingPid", s.aotTrainingPid())
                .put("cores", s.cores())
                .put("totalMemoryBytes", s.totalMemoryBytes())
                .put("freeMemoryBytes", s.freeMemoryBytes())
                .put("systemCpuLoad", s.systemCpuLoad());
    }

    /**
     * Quantized status for equality — available/heap to 1 MiB, CPU load to 1 percentage point,
     * counters exact. Keeps "still 5.0 GiB available" from spamming the wire.
     */
    record PresentStatus(
            int activePipelines,
            int activeRequests,
            int loadPp,
            long freeMib,
            long totalMib,
            long heapUsedMib,
            long heapCommittedMib,
            long rssMib,
            int cores,
            long pid,
            long aotTrainingPid) {

        static PresentStatus of(StatusSnapshot s) {
            int loadPp = s.systemCpuLoad() < 0 ? -1 : (int) Math.round(s.systemCpuLoad() * 100);
            return new PresentStatus(
                    s.activePipelines(),
                    s.activeRequests(),
                    loadPp,
                    mib(s.freeMemoryBytes()),
                    mib(s.totalMemoryBytes()),
                    mib(s.heapUsedBytes()),
                    mib(s.heapCommittedBytes()),
                    mib(s.rssBytes()),
                    s.cores(),
                    s.pid(),
                    s.aotTrainingPid());
        }

        private static long mib(long bytes) {
            if (bytes < 0) return -1;
            return bytes / (1024L * 1024L);
        }
    }

    /** Dual-surface cache fingerprint (1 MiB quanta on each surface + budgets). */
    record PresentCache(long actionMib, long artifactMib, long actionMaxMib, long storeMaxMib) {
        static PresentCache of(CacheSnapshot c) {
            return new PresentCache(
                    c.actionCacheBytes() / (1024L * 1024L),
                    c.artifactStorageBytes() / (1024L * 1024L),
                    c.actionMaxBytes() / (1024L * 1024L),
                    c.maxBytes() / (1024L * 1024L));
        }
    }
}
