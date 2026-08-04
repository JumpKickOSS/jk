// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.cache.DiskUsage;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkCacheConfig;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Storage breakdown for {@code GET /api/cache} and live {@code cache} SSE — the same two surfaces
 * the CLI splits as {@code jk cache storage} (cache CAS + action index + format stamps) and {@code
 * jk repo storage} (artifact store: store CAS + repos + run logs).
 *
 * <p>{@code maxBytes} is the <strong>artifact store</strong> budget ({@code [cache]
 * max-store-size-mb}, default 4 GiB). {@code actionMaxBytes} / {@code cacheMaxBytes} is the
 * <strong>cache tier</strong> budget ({@code [cache] max-cache-size-mb}, default 1 GiB).
 *
 * <p>Byte sizes are <em>exclusive</em> across store sections (CAS before repos) so hard-linked
 * {@code repos/} views do not double-count CAS blob allocations — same accounting as the CLI.
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
        long maxBytes,
        long actionMaxBytes,
        long lastPrunedMillis) {

    /** All section file counts (debug / legacy combined total). */
    public long totalCount() {
        return casCount
                + actionsCount
                + cacheCasCount
                + workerJarsCount
                + runLogsCount
                + formatStampsCount;
    }

    /** All section bytes (debug / legacy combined total — prefer the two surfaces below). */
    public long totalBytes() {
        return casBytes + actionsBytes + cacheCasBytes + workerJarsBytes + runLogsBytes + formatStampsBytes;
    }

    /**
     * Cache-tier footprint matching {@code jk cache storage}: action index + cache CAS + format
     * stamps.
     */
    public long actionCacheBytes() {
        return actionsBytes + cacheCasBytes + formatStampsBytes;
    }

    public long actionCacheCount() {
        return actionsCount + cacheCasCount + formatStampsCount;
    }

    /** Alias of {@link #actionCacheBytes()} — preferred name for the cache tier. */
    public long cacheBytes() {
        return actionCacheBytes();
    }

    /** Alias of {@link #actionMaxBytes} — preferred name for the cache budget. */
    public long cacheMaxBytes() {
        return actionMaxBytes;
    }

    /**
     * Artifact / store footprint matching {@code jk repo storage}: store CAS + worker JAR mirrors +
     * run logs.
     */
    public long artifactStorageBytes() {
        return casBytes + workerJarsBytes + runLogsBytes;
    }

    public long artifactStorageCount() {
        return casCount + workerJarsCount + runLogsCount;
    }

    /**
     * Walk store + cache sections and snapshot their sizes — identical dirs and hardlink-aware
     * exclusive byte accounting as {@code jk cache storage} / {@code jk repo storage}.
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

        JkCacheConfig cfg = resolveConfig();
        long storeMax = cfg.maxStoreSizeBytes();
        long cacheMax = cfg.maxCacheSizeBytes();
        long lastPruned = readLastPrunedMillis(cacheRoot);
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
                storeMax,
                cacheMax,
                lastPruned);
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
                .put("totalCount", totalCount())
                .put("totalBytes", totalBytes())
                .put("actionCacheCount", actionCacheCount())
                .put("actionCacheBytes", actionCacheBytes())
                .put("actionMaxBytes", actionMaxBytes)
                .put("cacheBytes", cacheBytes())
                .put("cacheMaxBytes", cacheMaxBytes())
                .put("artifactStorageCount", artifactStorageCount())
                .put("artifactStorageBytes", artifactStorageBytes())
                .put("maxBytes", maxBytes)
                .put("lastPrunedMillis", lastPrunedMillis);
    }

    /**
     * Thin live payload for footer chrome (JK-1502): dual surfaces + budgets only. Section
     * breakdown stays on REST / full {@link #toJson()}.
     */
    public JsonOut toThinJson() {
        return JsonOut.object()
                .put("thin", true)
                .put("actionCacheBytes", actionCacheBytes())
                .put("actionMaxBytes", actionMaxBytes)
                .put("cacheBytes", cacheBytes())
                .put("cacheMaxBytes", cacheMaxBytes())
                .put("artifactStorageBytes", artifactStorageBytes())
                .put("maxBytes", maxBytes)
                .put("lastPrunedMillis", lastPrunedMillis);
    }
}
