// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.cache.DiskUsage;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkCacheConfig;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Storage breakdown for {@code GET /api/cache} and live {@code cache} SSE — the same two surfaces
 * the CLI splits as {@code jk cache storage} (action cache) and {@code jk repo storage} (artifact
 * store: CAS + worker JAR mirrors + run logs).
 *
 * <p>{@code maxBytes} is the <strong>artifact store</strong> budget ({@code [cache] max-size-gb},
 * default 20 GiB). {@code actionMaxBytes} is the <strong>action cache</strong> budget ({@code
 * [cache] action-max-size-mb}, default 1 GiB). {@code lastPrunedMillis} is {@code 0} when never
 * pruned.
 *
 * <p>Byte sizes are <em>exclusive</em> across sections (CAS before repos) so hard-linked {@code
 * repos/} views do not double-count CAS blob allocations — same accounting as the CLI.
 */
public record CacheSnapshot(
        long casCount,
        long casBytes,
        long actionsCount,
        long actionsBytes,
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
        return casCount + actionsCount + workerJarsCount + runLogsCount + formatStampsCount;
    }

    /** All section bytes (debug / legacy combined total — prefer the two surfaces below). */
    public long totalBytes() {
        return casBytes + actionsBytes + workerJarsBytes + runLogsBytes + formatStampsBytes;
    }

    /**
     * Action-cache footprint matching {@code jk cache storage} — {@code actions/} only (format
     * stamps are listed separately in the Status panel).
     */
    public long actionCacheBytes() {
        return actionsBytes;
    }

    public long actionCacheCount() {
        return actionsCount;
    }

    /**
     * Artifact / store footprint matching {@code jk repo storage}: CAS + worker JAR mirrors + run
     * logs.
     */
    public long artifactStorageBytes() {
        return casBytes + workerJarsBytes + runLogsBytes;
    }

    public long artifactStorageCount() {
        return casCount + workerJarsCount + runLogsCount;
    }

    /**
     * Walk store + cache sections and snapshot their sizes — identical dirs and hardlink-aware
     * exclusive byte accounting as {@code jk cache storage} / {@code jk repo storage}. IO-shaped (a
     * full walk of the CAS), so callers invoke it per request or on a slow live tick, never on the
     * ~2s host-vitals sampler. Best-effort: an unreadable section counts as empty.
     */
    public static CacheSnapshot capture(Path cacheRoot) {
        // sha256/ and repos/ live under the store; actions/runs/stamps under the cache root.
        Path cas = JkStores.resolve(cacheRoot, "sha256");
        Path repos = JkStores.resolve(cacheRoot, "repos");
        Path actions = cacheRoot.resolve("actions");
        Path runs = cacheRoot.resolve("runs");
        Path stamps = cacheRoot.resolve("format-stamps");
        DiskUsage.Stats[] parts;
        try {
            // CAS first so hard-linked repo jars do not inflate worker-jar or total bytes.
            parts = DiskUsage.exclusive(cas, repos, actions, runs, stamps);
        } catch (Exception e) {
            parts = new DiskUsage.Stats[] {
                new DiskUsage.Stats(0, 0),
                new DiskUsage.Stats(0, 0),
                new DiskUsage.Stats(0, 0),
                new DiskUsage.Stats(0, 0),
                new DiskUsage.Stats(0, 0)
            };
        }
        JkCacheConfig cfg = resolveConfig();
        long storeMax = cfg.storeMaxSizeBytes();
        long actionMax = cfg.actionMaxSizeBytes();
        long lastPruned = readLastPrunedMillis(cacheRoot);
        return new CacheSnapshot(
                parts[0].files(),
                parts[0].bytes(),
                parts[2].files(),
                parts[2].bytes(),
                parts[1].files(),
                parts[1].bytes(),
                parts[3].files(),
                parts[3].bytes(),
                parts[4].files(),
                parts[4].bytes(),
                storeMax,
                actionMax,
                lastPruned);
    }

    private static JkCacheConfig resolveConfig() {
        try {
            return JkCacheConfig.resolve();
        } catch (Exception e) {
            return JkCacheConfig.DEFAULTS;
        }
    }

    /** The prune scheduler's stamp, or {@code 0} when the cache has never been pruned. */
    private static long readLastPrunedMillis(Path cacheRoot) {
        Path stamp = cacheRoot.resolve(cc.jumpkick.task.CachePruneScheduler.LAST_PRUNED_FILE);
        if (!Files.isRegularFile(stamp)) return 0;
        try {
            return Long.parseLong(Files.readString(stamp).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Full JSON for REST {@code GET /api/cache} and connect-hydrate when the Status panel needs sections. */
    public JsonOut toJson() {
        return JsonOut.object()
                .put("casCount", casCount)
                .put("casBytes", casBytes)
                .put("actionsCount", actionsCount)
                .put("actionsBytes", actionsBytes)
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
                .put("artifactStorageBytes", artifactStorageBytes())
                .put("maxBytes", maxBytes)
                .put("lastPrunedMillis", lastPrunedMillis);
    }
}
