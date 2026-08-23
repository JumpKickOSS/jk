// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.cache.DiskUsage;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.engine.JsonOut;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Storage breakdown for {@code GET /api/cache} and live {@code cache} SSE — the same two surfaces
 * the CLI splits as {@code jk cache usage} (action index + cache CAS) and {@code jk storage usage}
 * (artifact store: jars / natives / OCI / worker jars; run logs are state).
 *
 * <p>{@code actionMaxBytes} is the <strong>action cache</strong> budget ({@code [cache]
 * max-cache-size-gb}, default 4 GiB / 8 GiB on CI; small disks clamp that default). The
 * artifact store is reported but never budgeted.
 *
 * <p>Byte sizes are <em>exclusive</em> across store sections (store CAS before {@code repos/}) so
 * leftover hard links are not counted twice — same accounting as the CLI.
 *
 * <p>Prefer {@link #memoizing(Path)} for live engine paths: a full exclusive walk of a multi‑GiB
 * cache allocates tens of MiB of path/inode bookkeeping. Without single-flight + TTL, a dashboard
 * reconnect storm (several SSE tabs + {@code GET /api/cache}) can run that walk concurrent times
 * and leave SerialGC holding ~90 MiB committed at idle.
 */
public record CacheSnapshot(
        long casCount,
        long casBytes,
        long actionsCount,
        long actionsBytes,
        long cacheCasCount,
        long cacheCasBytes,
        long workerJarsCount,
        long workerJarsBytes,
        long runLogsCount,
        long runLogsBytes,
        long formatStampsCount,
        long formatStampsBytes,
        long actionMaxBytes,
        long lastPrunedMillis,
        long mavenLocalCount,
        long mavenLocalBytes) {

    /**
     * Default freshness for live {@code /api/cache} + SSE chrome. Deliberately half of
     * {@link LiveVitals#CACHE_PERIOD_MILLIS} (60 s): the safety-net sampler always sees a walk
     * at most this stale, while REST bursts and reconnect storms inside the window coalesce
     * onto one memoized result.
     */
    public static final long MEMO_TTL_MILLIS = 30_000L;

    /**
     * Supplier that walks at most once per TTL and coalesces concurrent callers onto a single
     * walk. Call {@link Memoizing#invalidate()} after builds so the next get is fresh.
     */
    public static Memoizing memoizing(Path cacheRoot) {
        return memoizing(cacheRoot, MEMO_TTL_MILLIS);
    }

    public static Memoizing memoizing(Path cacheRoot, long ttlMillis) {
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        return memoizing(() -> capture(cacheRoot), ttlMillis);
    }

    /** Test / custom loader seam — same single-flight + TTL as the path-based factory. */
    public static Memoizing memoizing(Supplier<CacheSnapshot> loader, long ttlMillis) {
        return new Memoizing(loader, ttlMillis);
    }

    /**
     * Single-flight, TTL-cached {@link #capture(Path)}. Safe to share across REST and LiveVitals.
     */
    public static final class Memoizing implements Supplier<CacheSnapshot> {
        private final Supplier<CacheSnapshot> loader;
        private final long ttlNanos;
        private final Object lock = new Object();
        private CacheSnapshot cached;
        private long deadlineNanos;
        /**
         * Explicit staleness flag. {@code System.nanoTime()} has an arbitrary — possibly
         * negative — origin, so an absolute sentinel like {@code deadlineNanos = 0} is not
         * reliably "expired": with a negative-origin clock {@code now - 0 < 0} held and
         * {@link #invalidate()} became a permanent no-op.
         */
        private boolean stale = true;

        Memoizing(Supplier<CacheSnapshot> loader, long ttlMillis) {
            this.loader = Objects.requireNonNull(loader, "loader");
            if (ttlMillis < 0) throw new IllegalArgumentException("ttlMillis < 0");
            this.ttlNanos = TimeUnit.MILLISECONDS.toNanos(ttlMillis);
        }

        @Override
        public CacheSnapshot get() {
            long now = System.nanoTime();
            Runtime rt = Runtime.getRuntime();
            CacheSnapshot snap;
            long usedBefore;
            synchronized (lock) {
                if (cached != null && !stale && now - deadlineNanos < 0) {
                    return cached;
                }
                usedBefore = rt.totalMemory() - rt.freeMemory();
                snap = loader.get();
                if (snap == null) {
                    return cached; // keep last good; callers tolerate null
                }
                cached = snap;
                deadlineNanos = System.nanoTime() + ttlNanos;
                stale = false;
            }
            // Exclusive walks allocate large temporary sets; SerialGC keeps "used" high until a
            // full collection. One post-walk GC after a fat capture keeps idle status honest —
            // OUTSIDE the memo lock, so coalesced callers return the just-published snapshot
            // instead of blocking through a stop-the-world collection too. Under
            // -XX:+DisableExplicitGC this is a no-op and the idle figure simply waits for a
            // natural full collection.
            long usedAfter = rt.totalMemory() - rt.freeMemory();
            if (usedAfter - usedBefore > 32L * 1024 * 1024) {
                System.gc();
            }
            return snap;
        }

        /** Drop TTL so the next {@link #get()} walks again (post-build / explicit refresh). */
        public void invalidate() {
            synchronized (lock) {
                stale = true;
            }
        }
    }

    /** All section file counts (debug / legacy combined total). */
    public long totalCount() {
        return casCount + actionsCount + cacheCasCount + workerJarsCount + runLogsCount + formatStampsCount;
    }

    /** All section bytes (debug / legacy combined total — prefer the two surfaces below). */
    public long totalBytes() {
        return casBytes + actionsBytes + cacheCasBytes + workerJarsBytes + runLogsBytes + formatStampsBytes;
    }

    /**
     * Action-cache footprint matching {@code jk cache usage} and the budget {@link
     * cc.jumpkick.task.ActionCachePrune} enforces: action index + cache CAS. Format stamps sit
     * under the same root but have their own count cap, so counting them here would meter the
     * budget bar against bytes no prune can reclaim.
     */
    public long actionCacheBytes() {
        return actionsBytes + cacheCasBytes;
    }

    public long actionCacheCount() {
        return actionsCount + cacheCasCount;
    }

    /**
     * Artifact / store footprint matching {@code jk storage usage}: store CAS + worker JAR mirrors.
     * Run logs are state (not storage) and are excluded from the total.
     */
    public long artifactStorageBytes() {
        return casBytes + workerJarsBytes;
    }

    public long artifactStorageCount() {
        return casCount + workerJarsCount;
    }

    /**
     * Maven local repository size — informational; jk neither budgets nor prunes it. Walked once
     * inside {@link #capture(Path)} and stored on the snapshot; never call this on the render / SSE
     * connect path, which must not walk a multi-GiB {@code ~/.m2} (JK-2293).
     */
    static DiskUsage.Stats mavenLocalStats() {
        try {
            return DiskUsage.of(cc.jumpkick.repo.M2Dirs.localRepository());
        } catch (Exception e) {
            return new DiskUsage.Stats(0, 0);
        }
    }

    /**
     * Walk store + cache sections and snapshot their sizes — identical dirs and hardlink-aware
     * exclusive byte accounting as {@code jk cache usage} / {@code jk storage usage}. Prefer
     * {@link #memoizing(Path)} on live engine paths so concurrent REST/SSE callers do not walk
     * the store in parallel.
     */
    public static CacheSnapshot capture(Path cacheRoot) {
        Path storeCas = JkStores.resolve(cacheRoot, "sha256");
        Path repos = JkStores.resolve(cacheRoot, "repos");
        Path actions = cacheRoot.resolve("actions");
        Path cacheCas = cacheRoot.resolve("sha256");
        Path runs = cacheRoot.resolve("runs");
        Path stamps = cacheRoot.resolve("format-stamps");
        DiskUsage.Stats[] parts;
        try {
            parts = DiskUsage.exclusive(storeCas, repos, actions, runs, stamps);
        } catch (Exception e) {
            parts = new DiskUsage.Stats[] {
                new DiskUsage.Stats(0, 0),
                new DiskUsage.Stats(0, 0),
                new DiskUsage.Stats(0, 0),
                new DiskUsage.Stats(0, 0),
                new DiskUsage.Stats(0, 0)
            };
        }
        DiskUsage.Stats cacheCasStats;
        try {
            cacheCasStats = DiskUsage.of(cacheCas);
        } catch (Exception e) {
            cacheCasStats = new DiskUsage.Stats(0, 0);
        }

        long cacheMax = resolveConfig().maxCacheSizeBytes();
        long lastPruned = readLastPrunedMillis(cacheRoot);
        DiskUsage.Stats m2 = mavenLocalStats(); // walked once here, never on the render/connect path
        return new CacheSnapshot(
                parts[0].files(),
                parts[0].bytes(),
                parts[2].files(),
                parts[2].bytes(),
                cacheCasStats.files(),
                cacheCasStats.bytes(),
                parts[1].files(),
                parts[1].bytes(),
                parts[3].files(),
                parts[3].bytes(),
                parts[4].files(),
                parts[4].bytes(),
                cacheMax,
                lastPruned,
                m2.files(),
                m2.bytes());
    }

    private static JkCacheConfig resolveConfig() {
        try {
            return JkCacheConfig.resolve();
        } catch (Exception e) {
            return JkCacheConfig.DEFAULTS;
        }
    }

    private static long readLastPrunedMillis(Path cacheRoot) {
        Path stamp = cacheRoot.resolve(cc.jumpkick.task.CachePruneScheduler.LAST_PRUNED_FILE);
        if (!Files.isRegularFile(stamp)) return 0L;
        try {
            return Long.parseLong(Files.readString(stamp).trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Full JSON for REST {@code GET /api/cache} and connect-hydrate when the Status panel needs sections. */
    public JsonOut toJson() {
        return JsonOut.object()
                .put("casCount", casCount)
                .put("casBytes", casBytes)
                .put("actionsCount", actionsCount)
                .put("actionsBytes", actionsBytes)
                .put("cacheCasCount", cacheCasCount)
                .put("cacheCasBytes", cacheCasBytes)
                .put("workerJarsCount", workerJarsCount)
                .put("workerJarsBytes", workerJarsBytes)
                .put("runLogsCount", runLogsCount)
                .put("runLogsBytes", runLogsBytes)
                .put("formatStampsCount", formatStampsCount)
                .put("formatStampsBytes", formatStampsBytes)
                // Count-cap for the stamp tree (512k default / 1M when CI=1|true) — web shows % used, not GiB.
                .put("formatStampsMax", cc.jumpkick.task.FormatStampGc.resolveMaxFiles())
                .put("totalCount", totalCount())
                .put("totalBytes", totalBytes())
                .put("actionCacheCount", actionCacheCount())
                .put("actionCacheBytes", actionCacheBytes())
                .put("actionMaxBytes", actionMaxBytes)
                .put("artifactStorageCount", artifactStorageCount())
                .put("artifactStorageBytes", artifactStorageBytes())
                .put("mavenLocalCount", mavenLocalCount)
                .put("mavenLocalBytes", mavenLocalBytes)
                .put("lastPrunedMillis", lastPrunedMillis);
    }

    /**
     * Thin live payload for footer chrome: both surfaces plus the cache budget. Section
     * breakdown stays on REST / full {@link #toJson()}.
     */
    public JsonOut toThinJson() {
        return JsonOut.object()
                .put("thin", true)
                .put("actionCacheBytes", actionCacheBytes())
                .put("actionMaxBytes", actionMaxBytes)
                .put("artifactStorageBytes", artifactStorageBytes())
                .put("mavenLocalBytes", mavenLocalBytes)
                .put("lastPrunedMillis", lastPrunedMillis);
    }
}
