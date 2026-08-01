// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.cache.DiskUsage;
import cc.jumpkick.cache.JkStores;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The cache-directory breakdown {@code GET /api/cache} reports — the same sections {@code jk cache
 * info} renders (CAS blobs, action cache, worker jars, run logs, format stamps), so the dashboard
 * and the CLI can never drift apart. {@code maxBytes} is the configured LRU ceiling ({@code [cache]
 * max-size-gb}, default 20 GiB) so the utilization meter always has a denominator;
 * {@code lastPrunedMillis} is {@code 0} when the cache has never been pruned.
 *
 * <p>Byte sizes are <em>exclusive</em> across sections (CAS before repos) so hard-linked
 * {@code repos/} views do not double-count CAS blob allocations — same accounting as the CLI.
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
        long lastPrunedMillis) {

    public long totalCount() {
        return casCount + actionsCount + workerJarsCount + runLogsCount + formatStampsCount;
    }

    public long totalBytes() {
        return casBytes + actionsBytes + workerJarsBytes + runLogsBytes + formatStampsBytes;
    }

    /**
     * Walk store + cache sections and snapshot their sizes — identical dirs and hardlink-aware
     * exclusive byte accounting as {@code jk cache info}. IO-shaped (a full walk of the CAS), so
     * callers invoke it per request, never on a hot path. Best-effort: an unreadable section
     * counts as empty.
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
        long maxBytes = configuredMaxBytes();
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
                maxBytes,
                lastPruned);
    }

    /** The configured LRU ceiling, or the documented 20 GiB default when unset/unreadable. */
    private static long configuredMaxBytes() {
        int gb = 20;
        try {
            gb = cc.jumpkick.config.JkCacheConfig.resolve().maxSizeGb().orElse(20);
        } catch (Exception ignored) {
            // keep the default
        }
        return gb * 1024L * 1024L * 1024L;
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
}
