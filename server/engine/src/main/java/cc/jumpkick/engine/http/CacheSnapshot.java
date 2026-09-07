// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import cc.jumpkick.cache.DiskUsage;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.engine.api.JsonOut;
import cc.jumpkick.host.ActionTree;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.repo.M2Dirs;
import cc.jumpkick.task.CachePruneScheduler;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Storage breakdown for {@code GET /api/cache} and live {@code cache} SSE — the same two surfaces
 * the CLI splits as {@code jk cache usage} (action index + cache CAS) and {@code jk storage usage}
 * (artifact store: jars / natives / OCI / worker jars; run logs are state).
 *
 * <p>{@code actionMaxBytes} is the <strong>action cache</strong> budget ({@code [cache]
 * max-cache-size-gb}, default 4 GiB / 8 GiB on CI; small disks clamp that default). The
 * artifact store is reported but never budgeted. {@code incrementalMaxBytes} is the separate
 * Zinc-analysis budget ({@code incremental-max-size-gb}); {@code actionsBytes} excludes the
 * incremental trees for that reason, so each bar measures its own tier.
 *
 * <p>Byte sizes are <em>exclusive</em> across store sections (store CAS before {@code repos/}) so
 * leftover hard links are not counted twice — same accounting as the CLI.
 *
 * <p>{@code totalBytes} is the <strong>cache root walked as one tree</strong> — the same number
 * {@code jk status} prints as "Size on Disk" and the same one {@code jk cache nuke} frees. It used
 * to be a five-term sum of section fields, three cache and two store, which is the hand list
 * retired on the CLI side for two independent reasons: it counted store bytes a nuke
 * leaves, and it missed every cache tier nobody had thought to add to it ({@code hash-memo},
 * {@code kotlin-cp-snapshots}, {@code base-jre}, …). A directory is total by construction; a sum of
 * named sections is total only until the next tier lands. The store keeps its own figure in
 * {@link #artifactStorageBytes()}, which is what a "combined" reader actually wanted.
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
        long formatStampsCount,
        long formatStampsBytes,
        long incrementalCount,
        long incrementalBytes,
        long incrementalMaxBytes,
        long actionMaxBytes,
        long lastPrunedMillis,
        long mavenLocalCount,
        long mavenLocalBytes,
        long derivedCount,
        long derivedBytes,
        long totalCount,
        long totalBytes) {

    /**
     * Default freshness for live {@code /api/cache} + SSE chrome. Deliberately half of
     * {@link LiveVitals#CACHE_PERIOD_MILLIS} (60 s): the safety-net sampler always sees a walk
     * at most this stale, while REST bursts and reconnect storms inside the window coalesce
     * onto one memoized result.
     */
    public static final long MEMO_TTL_MILLIS = 30_000L;

    /** Tiers the report breaks out by name; everything else in the table sums into {@code derived}. */
    private static final Set<CacheTree> OWN_ROW =
            EnumSet.of(CacheTree.ACTIONS, CacheTree.CACHE_CAS, CacheTree.FORMAT_STAMPS);

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
        private @Nullable CacheSnapshot cached;
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
     * connect path, which must not walk a multi-GiB {@code ~/.m2}.
     */
    static DiskUsage.Stats mavenLocalStats() {
        try {
            return DiskUsage.of(M2Dirs.localRepository());
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
        Path storeCas = JkStores.resolve("sha256");
        Path repos = JkStores.resolve("repos");
        Path actions = CacheTree.ACTIONS.under(cacheRoot);
        Path cacheCas = CacheTree.CACHE_CAS.under(cacheRoot);
        Path stamps = CacheTree.FORMAT_STAMPS.under(cacheRoot);
        DiskUsage.Stats[] parts;
        try {
            parts = DiskUsage.exclusive(storeCas, repos, actions, stamps);
        } catch (Exception e) {
            parts = new DiskUsage.Stats[] {
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

        JkCacheConfig config = resolveConfig();
        DiskUsage.Stats incremental = incrementalStats(actions);
        long lastPruned = readLastPrunedMillis(cacheRoot);
        DiskUsage.Stats m2 = mavenLocalStats(); // walked once here, never on the render/connect path
        DiskUsage.Stats derived = derivedStats(cacheRoot);
        // One walk of the whole root, deliberately not a sum of the sections above: see the class
        // javadoc. Absent root reads as zero, exactly as `jk status` renders it.
        DiskUsage.Stats wholeRoot;
        try {
            wholeRoot = DiskUsage.of(cacheRoot);
        } catch (Exception e) {
            wholeRoot = new DiskUsage.Stats(0, 0);
        }
        return new CacheSnapshot(
                parts[0].files(),
                parts[0].bytes(),
                Math.max(0L, parts[2].files() - incremental.files()),
                Math.max(0L, parts[2].bytes() - incremental.bytes()),
                cacheCasStats.files(),
                cacheCasStats.bytes(),
                parts[1].files(),
                parts[1].bytes(),
                parts[3].files(),
                parts[3].bytes(),
                incremental.files(),
                incremental.bytes(),
                config.incrementalMaxSizeBytes(),
                config.maxCacheSizeBytes(),
                lastPruned,
                m2.files(),
                m2.bytes(),
                derived.files(),
                derived.bytes(),
                wholeRoot.files(),
                wholeRoot.bytes());
    }

    /**
     * Every tier under the cache root that has no row of its own — the small derived caches that
     * carry their own retention rather than the action budget. Driven off {@link CacheTree}
     * rather than a list here, so a tier added to the table shows up in the report without anyone
     * remembering this file.
     */
    private static DiskUsage.Stats derivedStats(Path cacheRoot) {
        long files = 0;
        long bytes = 0;
        for (CacheTree tier : CacheTree.cached()) {
            if (OWN_ROW.contains(tier)) continue;
            try {
                DiskUsage.Stats stats = DiskUsage.of(tier.under(cacheRoot));
                files += stats.files();
                bytes += stats.bytes();
            } catch (Exception unreadable) {
                // a tier that cannot be walked contributes nothing, like an absent one
            }
        }
        return new DiskUsage.Stats(files, bytes);
    }

    /**
     * Zinc analysis trees under {@code actions/}. Reported apart from the action index because they
     * are bounded apart from it — see {@code ActionCachePrune.Policy}.
     */
    private static DiskUsage.Stats incrementalStats(Path actionsDir) {
        long files = 0;
        long bytes = 0;
        for (Path dir : ActionTree.incrementalUnder(actionsDir)) {
            try {
                DiskUsage.Stats tree = DiskUsage.of(dir);
                files += tree.files();
                bytes += tree.bytes();
            } catch (Exception unreadable) {
                // absent or mid-delete — counts as empty, same as every other section here
            }
        }
        return new DiskUsage.Stats(files, bytes);
    }

    private static JkCacheConfig resolveConfig() {
        try {
            return JkCacheConfig.resolve();
        } catch (Exception e) {
            return JkCacheConfig.DEFAULTS;
        }
    }

    private static long readLastPrunedMillis(Path cacheRoot) {
        return CachePruneScheduler.read(cacheRoot)
                .map(CachePruneScheduler.Stamp::millis)
                .orElse(0L);
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
                .put("formatStampsCount", formatStampsCount)
                .put("formatStampsBytes", formatStampsBytes)
                .put("incrementalCount", incrementalCount)
                .put("incrementalBytes", incrementalBytes)
                // Zinc analysis is budgeted apart from the action index: own bar, own denominator.
                .put("incrementalMaxBytes", incrementalMaxBytes)
                // Count-cap for the stamp tree — web shows % of the cap used, never GiB.
                // The cache root as one tree — `jk status`'s "Size on Disk", and what a nuke
                // frees. Not the sum of the section fields above, and not the store.
                .put("totalCount", totalCount)
                .put("totalBytes", totalBytes)
                .put("actionCacheCount", actionCacheCount())
                .put("actionCacheBytes", actionCacheBytes())
                .put("actionMaxBytes", actionMaxBytes)
                .put("artifactStorageCount", artifactStorageCount())
                .put("artifactStorageBytes", artifactStorageBytes())
                .put("mavenLocalCount", mavenLocalCount)
                .put("mavenLocalBytes", mavenLocalBytes)
                // Apparent bytes, and no denominator: these tiers are bounded by count or by
                // supersession, and a bar against a number that is not their bound would be a
                // fiction. See CacheTier on why `du` disagrees with all of them anyway.
                .put("derivedCount", derivedCount)
                .put("derivedBytes", derivedBytes)
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
