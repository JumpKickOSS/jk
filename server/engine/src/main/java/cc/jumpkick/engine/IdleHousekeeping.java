// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.builds.MetricsHarvest;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.verbs.CacheMaintenanceLocks;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.resolve.ResolveProcessCacheControl;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.CachePlans;
import cc.jumpkick.runtime.base.BuildMetrics;
import cc.jumpkick.runtime.base.TestClassWalls;
import cc.jumpkick.task.AbiMemo;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.CachePruneScheduler;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Idle-boundary chores: cache prune, journal/metrics/heap-dump retention, host warmup, trailing
 * GC. Exactly-once at the build boundary; GC is always last.
 */
@RequiredArgsConstructor
public final class IdleHousekeeping {

    /** How long an OutOfMemoryError heap dump stays beside the engine log before the boundary deletes it. */
    static final Duration HEAP_DUMP_RETENTION = Duration.ofDays(7);

    private final AtomicInteger activeBuildPlans;
    private final ReentrantReadWriteLock cacheGate;
    private final JkHistoryConfig historyConfig;
    private final BuildJournal journal;
    private final Supplier<Path> metricsFile;
    private final Path engineDir;
    private final LongSupplier clock;
    private final Consumer<String> log;
    private final BooleanSupplier shuttingDown;
    private final BooleanSupplier draining;
    private final Runnable onDrainIdle;

    private final AtomicReference<@Nullable Path> pendingPruneCache = new AtomicReference<>();
    private final AtomicReference<@Nullable Boolean> pendingWarmupForce = new AtomicReference<>();
    private final AtomicBoolean warmupRunning = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * After a plan slot was released: all idle housekeeping when nothing remains in flight.
     * Does not decrement the counter — the finish path decrements first.
     */
    public void maybeIdleBoundary() {
        if (activeBuildPlans.get() != 0) return;
        run();
        if (draining.getAsBoolean()) onDrainIdle.run();
    }

    public void maybeIdleGc() {
        if (activeBuildPlans.get() != 0 || warmupRunning.get()) return;
        System.gc();
    }

    public boolean warmupRunning() {
        return warmupRunning.get();
    }

    public void run() {
        if (shuttingDown.getAsBoolean()) return;
        if (activeBuildPlans.get() != 0) return;
        if (!running.compareAndSet(false, true)) return;
        try {
            if (activeBuildPlans.get() != 0) return;
            drainPendingPrune();
            pruneJournal();
            pruneMetrics();
            pruneHeapDumps();
            try {
                MetricsHarvest.get().awaitIdle(30_000L);
            } catch (RuntimeException ignored) {
            }
            if (activeBuildPlans.get() != 0) return;

            if (pendingWarmupForce.get() != null || HostWarmup.needsWork()) {
                pendingWarmupForce.compareAndSet(null, Boolean.FALSE);
                kickPendingWarmup(true);
                return;
            }
            if (activeBuildPlans.get() == 0 && !warmupRunning.get()) {
                dropHeapResidue();
                System.gc();
            }
        } finally {
            running.set(false);
        }
    }

    public void maybeEnqueuePrune(Path cache) {
        try {
            var config = JkCacheConfig.resolve();
            if (config.autoPrune() && CachePruneScheduler.shouldRun(config, cache)) {
                pendingPruneCache.compareAndSet(null, cache);
            }
        } catch (IOException ignored) {
            // hygiene, never load-bearing
        }
    }

    /** 12-hour feed-refresh hook. Does not consult {@code .last-pruned}. */
    public void enqueueScheduledCachePrune() {
        if (shuttingDown.getAsBoolean()) return;
        var config = JkCacheConfig.resolve();
        if (config.autoPrune()) {
            pendingPruneCache.compareAndSet(null, JkDirs.cache());
        }
        if (activeBuildPlans.get() == 0) {
            pendingWarmupForce.compareAndSet(null, Boolean.FALSE);
            run();
        } else {
            scheduleHostWarmup(false);
        }
    }

    public boolean scheduleHostWarmup(boolean force) {
        if (shuttingDown.getAsBoolean() || draining.getAsBoolean()) return false;
        if (!force && !HostWarmup.needsWork()) return false;
        pendingWarmupForce.updateAndGet(prev -> prev == null ? force : (prev || force));
        if (activeBuildPlans.get() == 0) kickPendingWarmup(true);
        return true;
    }

    public void scheduleResolveClassWarmup() {
        if (shuttingDown.getAsBoolean() || draining.getAsBoolean()) return;
        Thread.ofVirtual().name("jk-resolve-warmup").start(() -> {
            try {
                Class.forName("cc.jumpkick.resolver.pubgrub.PubGrubSolver");
                Class.forName("cc.jumpkick.resolver.pubgrub.PartialSolution");
                Class.forName("cc.jumpkick.resolver.MavenPackageSource");
                Class.forName("cc.jumpkick.resolver.LockOrchestrator");
                Class.forName("cc.jumpkick.repo.EffectivePomBuilder");
                Class.forName("cc.jumpkick.resolve.ResolveProcessCacheControl");
            } catch (ClassNotFoundException | LinkageError ignored) {
                // best-effort
            }
        });
    }

    private void kickPendingWarmup(boolean trailGc) {
        if (shuttingDown.getAsBoolean() || draining.getAsBoolean()) return;
        if (activeBuildPlans.get() != 0) return;
        Boolean force = pendingWarmupForce.getAndSet(null);
        if (force == null) return;
        if (!warmupRunning.compareAndSet(false, true)) {
            pendingWarmupForce.updateAndGet(prev -> prev == null ? force : (prev || force));
            return;
        }
        Thread t = new Thread(
                () -> {
                    try {
                        if (activeBuildPlans.get() != 0) {
                            pendingWarmupForce.updateAndGet(prev -> prev == null ? force : (prev || force));
                            return;
                        }
                        drainPendingPrune();
                        try {
                            MetricsHarvest.get().awaitIdle(30_000L);
                        } catch (RuntimeException ignored) {
                        }
                        HostWarmup.runIdle(force, log);
                    } catch (RuntimeException e) {
                        log.accept("jk engine: idle host warmup failed: " + e.getMessage());
                    } finally {
                        boolean more = pendingWarmupForce.get() != null;
                        if (trailGc && !more && activeBuildPlans.get() == 0) {
                            dropHeapResidue();
                            System.gc();
                        }
                        warmupRunning.set(false);
                        if (pendingWarmupForce.get() != null && activeBuildPlans.get() == 0) {
                            kickPendingWarmup(trailGc);
                        }
                    }
                },
                "jk-idle-warmup");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Drop process-wide memos whose payoff is intra-build so the trailing GC has something to
     * reclaim: resolve memos (rebuilt cheaply from the on-disk caches), the action-cache last-use
     * stamp memo (so a long-lived engine re-stamps rather than freezing the ranking), the metrics
     * aggregate, and any unclaimed test-wall snapshots. All are optimisations, never correctness.
     *
     * <p>The file-hash and ABI memos are persisted here rather than dropped: they do not grow with
     * the number of builds, and their whole payoff is the build after this one.
     */
    private void dropHeapResidue() {
        dropHeapResidue(historyConfig.enabled());
    }

    /**
     * Release the per-build memos. The class-wall buffer is dropped only when history is on: the
     * finish path has harvested it into {@code metrics.toml} by then and the estimator reads it
     * back from disk. With history off there is no harvest, so the buffer is the only cross-build
     * test ETA the process has, and an idle boundary must not reset it to cold priors.
     */
    static void dropHeapResidue(boolean historyEnabled) {
        try {
            FileHashMemo.flush();
            AbiMemo.flush();
            ActionCache.clearStampCache();
            ResolveProcessCacheControl.clearAll();
            BuildMetrics.clearSessionAggregatesMemo();
            if (historyEnabled) TestClassWalls.takeAll();
        } catch (RuntimeException ignored) {
            // hygiene, never load-bearing
        }
    }

    private void pruneJournal() {
        if (!historyConfig.enabled()) return;
        try {
            long now = clock.getAsLong();
            BuildJournal.PruneResult r = journal.prune(historyConfig.maxAgeMillis(), historyConfig.maxDiskBytes(), now);
            if (r.removedEntries() > 0) {
                log.accept("jk engine: build journal prune removed " + r.removedEntries() + " entries ("
                        + r.removedBytes() + " bytes)");
            }
        } catch (RuntimeException e) {
            log.accept("jk engine: build journal prune failed: " + e.getMessage());
        }
    }

    private void pruneMetrics() {
        try {
            BuildMetrics.Limits limits = BuildMetrics.Limits.resolve(JkDirs.userConfigFile(), System::getenv);
            BuildMetrics.PruneReport r = BuildMetrics.prune(metricsFile.get(), limits, clock.getAsLong(), false);
            if (r.evictedByAge() + r.evictedBySize() > 0) {
                log.accept("jk engine: build metrics prune removed " + (r.evictedByAge() + r.evictedBySize())
                        + " rows (" + r.kept() + " kept, " + r.finalBytes() + " bytes)");
            }
        } catch (RuntimeException e) {
            log.accept("jk engine: build metrics prune failed: " + e.getMessage());
        }
    }

    private void pruneHeapDumps() {
        try {
            int removed = pruneHeapDumps(engineDir, clock.getAsLong());
            if (removed > 0)
                log.accept("jk engine: removed " + removed + " heap dump(s) older than " + HEAP_DUMP_RETENTION.toDays()
                        + " days");
        } catch (IOException | RuntimeException e) {
            log.accept("jk engine: heap dump prune failed: " + e.getMessage());
        }
    }

    /**
     * Delete every {@code .hprof} under {@code engineDir} last modified before the retention
     * window. The dump is the post-mortem the next client points at, so it is kept long enough to
     * be looked at, and no longer — a dump is as large as the heap cap.
     */
    static int pruneHeapDumps(Path engineDir, long nowMillis) throws IOException {
        long cutoff = nowMillis - HEAP_DUMP_RETENTION.toMillis();
        int[] removed = {0};
        // Dumps live flat in the engine dir; nothing below it is walked.
        PathUtil.forEachRegularFile(engineDir, dir -> !dir.equals(engineDir), (file, attrs) -> {
            if (!file.getFileName().toString().endsWith(".hprof")) return;
            if (attrs.lastModifiedTime().toMillis() < cutoff && Files.deleteIfExists(file)) removed[0]++;
        });
        return removed[0];
    }

    /**
     * Run the queued prune, if the two maintenance locks are free. A cache root that is not on
     * disk is skipped outright: {@code jk cache nuke} removes that root and leaves this engine
     * running, so a boundary that asserted the tree back would undo the command, and one that
     * merely tripped over the missing lock file logged a prune failure at every boundary from
     * then on. Locks are taken through {@link CacheMaintenanceLocks} rather than re-derived here
     * — a second copy of the protocol is how the two came to disagree about that.
     */
    private void drainPendingPrune() {
        Path cache = pendingPruneCache.getAndSet(null);
        if (cache == null) return;
        if (!Files.isDirectory(cache)) return;
        try {
            // Busy locks re-queue rather than drop the pass: the next boundary is soon and a
            // prune skipped for good is a cache that grows past its budget in silence.
            if (!CacheMaintenanceLocks.tryExclusively(cacheGate, cache, () -> prunePass(cache))) {
                pendingPruneCache.compareAndSet(null, cache);
            }
        } catch (Exception e) {
            log.accept("jk engine: idle-boundary cache prune failed: " + e.getMessage());
        }
    }

    /** The prune itself; runs with both maintenance locks held. */
    private void prunePass(Path cache) {
        // Scratch is a single ambient directory, not a per-root one: sweeping it while
        // pruning some other cache root would reach outside the root asked for.
        boolean ambient = cache.equals(JkDirs.cache());
        BuildPlan plan = CachePlans.pruneBuildPlan(cache, false, ambient);
        BuildPlanResult result = plan.run();
        if (result.success()) {
            CachePruneScheduler.write(
                    cache,
                    clock.getAsLong(),
                    plan.get(CachePlans.FINAL_ACTION_BYTES).orElse(-1L));
            log.accept("jk engine: idle-boundary cache prune removed "
                    + plan.get(CachePlans.FILES).orElse(0L)
                    + " files ("
                    + plan.get(CachePlans.BYTES).orElse(0L)
                    + " bytes)");
        } else {
            log.accept("jk engine: idle-boundary cache prune failed");
        }
    }
}
