// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.builds.MetricsHarvest;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.config.JkHistoryConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.decisions.DecisionQueue;
import cc.jumpkick.engine.jobs.LastBuiltRoot;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.engine.verbs.CacheMaintenanceLocks;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.runtime.CachePlans;
import cc.jumpkick.runtime.base.BuildMetrics;
import cc.jumpkick.task.AbiMemo;
import cc.jumpkick.task.CachePruneScheduler;
import cc.jumpkick.task.CacheStats;
import cc.jumpkick.task.FileHashMemo;
import cc.jumpkick.task.PruneYield;
import cc.jumpkick.util.TestHomes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
 * GC and native-heap trim ({@link HeapTrim}). Exactly-once at the build boundary; GC is always
 * last, and a settled trim follows once the engine has been idle for {@link HeapTrim#SETTLE}. A
 * draining engine prunes and warms nothing: its successor owns the shared stores.
 */
@RequiredArgsConstructor
public final class IdleHousekeeping {

    /**
     * How long an engine whose home is a test-home slot may sit idle before it exits. Its suite
     * stops it when the slot closes; a suite that was killed never does, and nothing else would.
     */
    static final Duration TEST_HOME_IDLE = Duration.ofMinutes(30);

    /** How long an OutOfMemoryError heap dump stays beside the engine log before the boundary deletes it. */
    static final Duration HEAP_DUMP_RETENTION = Duration.ofDays(7);

    /**
     * Idle prunes of one cache root that may yield in a row before the next runs to completion,
     * builds waiting on the gate for it. Without a bound, builds that arrive more often than a pass
     * takes keep every pass from finishing, and the cache grows past its budget for good.
     */
    static final int MAX_YIELDED_PASSES = 3;

    private final AtomicInteger activeBuildPlans;
    private final ReentrantReadWriteLock cacheGate;
    private final JkHistoryConfig historyConfig;
    private final BuildJournal journal;
    /** The decisions the journal prune drops settled ones from, so they leave the live queue with their files. */
    private final DecisionQueue decisions;

    private final Supplier<Path> metricsFile;
    /** The metrics store's size and age budget, read at each prune: the engine's config, not the process's. */
    private final Supplier<BuildMetrics.Limits> metricsLimits;

    private final Path engineDir;
    private final LongSupplier clock;
    private final Consumer<String> log;
    private final BooleanSupplier shuttingDown;
    private final BooleanSupplier draining;
    private final Runnable onDrainIdle;

    private final AtomicReference<@Nullable Path> pendingPruneCache = new AtomicReference<>();
    private final AtomicBoolean pendingWarmup = new AtomicBoolean();
    private final AtomicBoolean warmupRunning = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong testHomeIdle = new AtomicLong();

    /** The cache root whose last idle prunes yielded, and how many did in a row; null after one finished. */
    private final AtomicReference<@Nullable YieldStreak> yieldStreak = new AtomicReference<>();

    /** {@code passes} idle prunes of {@code cache} in a row stopped for a build. */
    private record YieldStreak(Path cache, int passes) {}

    /** Runs at each safe point of the idle prune before it asks whether a build waits; a test seam. */
    private volatile Runnable pruneSafePoint = () -> {};

    /**
     * After a plan slot was released: all idle housekeeping when nothing remains in flight.
     * Does not decrement the counter — the finish path decrements first.
     */
    public void maybeIdleBoundary() {
        if (activeBuildPlans.get() != 0) return;
        run();
        if (draining.getAsBoolean()) onDrainIdle.run();
        else HeapTrim.later(this::idle, this::settledTrim, HeapTrim.SETTLE);
        armTestHomeExit();
    }

    /**
     * For an engine in a test-home slot, exit once it has stayed idle for {@link #TEST_HOME_IDLE}:
     * each call restarts the wait. Armed at startup and at every idle boundary; no-op elsewhere.
     */
    public void armTestHomeExit() {
        if (!TestHomes.contains(engineDir)) return;
        long mine = testHomeIdle.incrementAndGet();
        // Engine-lifetime chore: it reads no request's session.
        SessionContext.startVirtual("jk-test-home-idle", () -> {
            try {
                Thread.sleep(TEST_HOME_IDLE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (testHomeIdle.get() != mine || !idle()) return;
            log.accept("jk engine: test-home engine idle for " + TEST_HOME_IDLE.toMinutes() + " min; exiting");
            onDrainIdle.run();
        });
    }

    private boolean idle() {
        return activeBuildPlans.get() == 0 && !warmupRunning.get() && !shuttingDown.getAsBoolean();
    }

    /**
     * The trim that runs once the engine has sat idle for {@link HeapTrim#SETTLE}, which is also when
     * the engine startup cache is recorded if this install has none: drop the
     * per-build memos again (the harvest thread and client disconnects allocate after the
     * boundary), empty the process-wide memos a next build of the same workspace would have
     * reused ({@link MemoTrim}), collect so the heap uncommits, and return the native heap. One
     * log line says what came back and what was dropped.
     */
    private void settledTrim() {
        if (!idle()) return;
        // A settled idle, not the boundary: the user's next build is less likely to land on it.
        if (!draining.getAsBoolean()) EngineAotTrainer.maybeTrain(this::idle, log);
        dropHeapResidue();
        String memos = MemoTrim.drop(LastBuiltRoot.get());
        System.gc();
        log.accept("jk engine: idle trim: " + HeapTrim.trimNative() + "; " + memos);
    }

    /**
     * A plan job entered workspace {@code entered} straight after one in {@code left}. Back-to-back
     * jobs never let the settled trim run, so the memos of every workspace the engine passed
     * through would pile up until a large one ran the capped heap out. With no other plan live
     * (the entering job holds the only slot), drop them as the settled trim does, keeping {@code
     * entered}'s, and collect before the new job starts allocating. The log line names the heap
     * before and after.
     */
    public void switchedWorkspace(Path left, Path entered) {
        if (activeBuildPlans.get() > 1) return;
        long before = usedHeap();
        String memos = MemoTrim.drop(entered);
        System.gc();
        log.accept("jk engine: workspace switch " + left + " to " + entered + ": heap " + (before >> 20) + " -> "
                + (usedHeap() >> 20) + " MiB; " + memos);
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
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
            // A draining engine's successor is already building out of the same cache, journal
            // and metrics, and this engine's cache gate does not reach that one's jobs: pruning
            // under them is the successor's chore, at its own boundary.
            if (!draining.getAsBoolean()) {
                drainPendingPrune();
                pruneJournal();
                pruneMetrics();
                pruneHeapDumps();
                CacheStats.flush(JkDirs.state().resolve(CacheStats.FILE_NAME));
            }
            try {
                MetricsHarvest.get().awaitIdle(30_000L);
            } catch (RuntimeException e) {
                Log.debug("run: RuntimeException ignored", e);
            }
            if (activeBuildPlans.get() != 0) return;

            if (pendingWarmup.get() || HostWarmup.needsWork()) {
                pendingWarmup.set(true);
                kickPendingWarmup(true);
                return;
            }
            if (activeBuildPlans.get() == 0 && !warmupRunning.get()) {
                dropHeapResidue();
                System.gc();
                HeapTrim.trimNative();
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

    /**
     * The disk is nearly full: prune {@code cache} now, whatever its cadence says. Runs on the
     * caller's thread when no build is live; otherwise the pass is queued for the build boundary, and
     * meanwhile no heavy blob is written ({@link cc.jumpkick.config.DiskGuard}). A draining engine
     * leaves it to its successor. True when a pass ran.
     */
    public boolean pruneNow(Path cache) {
        if (shuttingDown.getAsBoolean() || draining.getAsBoolean()) return false;
        pendingPruneCache.set(cache);
        if (activeBuildPlans.get() != 0) return false;
        drainPendingPrune();
        return true;
    }

    /**
     * The routine pass of the background GC: queue a prune of {@code cache} when its cadence calls
     * for one, and run it on the caller's thread when no build is live. A build that arrives makes the
     * pass yield at its next safe point.
     */
    public void routinePrune(Path cache) {
        if (shuttingDown.getAsBoolean() || draining.getAsBoolean()) return;
        maybeEnqueuePrune(cache);
        if (activeBuildPlans.get() == 0) drainPendingPrune();
    }

    /** 12-hour feed-refresh hook. Does not consult {@code .last-pruned}. */
    public void enqueueScheduledCachePrune() {
        if (shuttingDown.getAsBoolean()) return;
        var config = JkCacheConfig.resolve();
        if (config.autoPrune()) {
            pendingPruneCache.compareAndSet(null, JkDirs.cache());
        }
        if (activeBuildPlans.get() == 0) {
            pendingWarmup.set(true);
            run();
        } else {
            scheduleHostWarmup();
        }
    }

    /** Queue the idle calibration pass when the host needs one; runs now if no build is live. */
    public boolean scheduleHostWarmup() {
        if (shuttingDown.getAsBoolean() || draining.getAsBoolean()) return false;
        if (!HostWarmup.needsWork()) return false;
        pendingWarmup.set(true);
        if (activeBuildPlans.get() == 0) kickPendingWarmup(true);
        return true;
    }

    public void scheduleResolveClassWarmup() {
        if (shuttingDown.getAsBoolean() || draining.getAsBoolean()) return;
        // Idle-time class preload; reads no session.
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
        if (!pendingWarmup.getAndSet(false)) return;
        if (!warmupRunning.compareAndSet(false, true)) {
            pendingWarmup.set(true);
            return;
        }
        // Idle-time host warmup runs only while no build is live; reads no session.
        Thread t = new Thread(
                () -> {
                    try {
                        if (activeBuildPlans.get() != 0) {
                            pendingWarmup.set(true);
                            return;
                        }
                        drainPendingPrune();
                        try {
                            MetricsHarvest.get().awaitIdle(30_000L);
                        } catch (RuntimeException e) {
                            Log.debug("kickPendingWarmup: RuntimeException ignored", e);
                        }
                        HostWarmup.runIdle(log);
                    } catch (RuntimeException e) {
                        log.accept("jk engine: idle host warmup failed: " + e.getMessage());
                    } finally {
                        boolean more = pendingWarmup.get();
                        if (trailGc && !more && activeBuildPlans.get() == 0) {
                            dropHeapResidue();
                            System.gc();
                            HeapTrim.trimNative();
                        }
                        warmupRunning.set(false);
                        if (pendingWarmup.get() && activeBuildPlans.get() == 0) {
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
     * reclaim: the metrics aggregate. The resolve memos are not among them: they are bounded, and
     * the settled trim ({@link MemoTrim}) drops them once the engine has sat idle, so a build that
     * follows a lock inside that window still finds its POMs built. All are optimisations, never
     * correctness.
     *
     * <p>The file-hash and ABI memos are persisted here rather than dropped: they do not grow with
     * the number of builds, and their whole payoff is the build after this one.
     */
    private static void dropHeapResidue() {
        try {
            FileHashMemo.flush();
            AbiMemo.flush();
            BuildMetrics.clearSessionAggregatesMemo();
        } catch (RuntimeException e) {
            // hygiene, never load-bearing
            Log.debug("dropHeapResidue: hygiene, never load-bearing", e);
        }
    }

    /**
     * How often an idle boundary prunes the journal: a prune sizes every retained run, and an agent
     * looping short builds reaches a boundary after every one of them.
     */
    static final long JOURNAL_PRUNE_EVERY_MILLIS = 60 * 60 * 1000L;

    /** When the journal was last pruned; the first boundary after start always prunes. */
    private long journalPrunedAt = Long.MIN_VALUE;

    void pruneJournal() {
        if (!historyConfig.enabled()) return;
        long now = clock.getAsLong();
        if (journalPrunedAt != Long.MIN_VALUE && now - journalPrunedAt < JOURNAL_PRUNE_EVERY_MILLIS) return;
        journalPrunedAt = now;
        try {
            BuildJournal.PruneResult r =
                    journal.prune(historyConfig.maxAgeMillis(), historyConfig.maxDiskBytes(), now, decisions::prune);
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
            BuildMetrics.PruneReport r =
                    BuildMetrics.prune(metricsFile.get(), metricsLimits.get(), clock.getAsLong(), false);
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

    /**
     * The prune itself; runs with both maintenance locks held. A build that queues on the cache gate
     * meanwhile, or is admitted at all, stops the pass at its next safe point ({@link PruneYield}):
     * the gate is released, the build runs, and the pass is queued again whole for the next
     * boundary, its cadence stamp unwritten.
     *
     * <p>After {@link #MAX_YIELDED_PASSES} such stops in a row the next pass does not yield: it
     * holds the gate to the end and the builds that queue meanwhile wait for it. A pass that kept
     * the phases it had finished and resumed would not bound this, since one phase over a large
     * cache can outlast the gap between builds on its own, and it would have to carry totals and
     * judge per phase what a build in between made stale. A whole pass collects its roots afresh
     * and needs no such judgement.
     */
    private void prunePass(Path cache) {
        // Scratch is a single ambient directory, not a per-root one: sweeping it while
        // pruning some other cache root would reach outside the root asked for.
        boolean ambient = cache.equals(JkDirs.cache());
        YieldStreak streak = yieldStreak.get();
        int yieldedBefore = streak != null && streak.cache().equals(cache) ? streak.passes() : 0;
        boolean mustFinish = yieldedBefore >= MAX_YIELDED_PASSES;
        if (mustFinish) {
            log.accept("jk engine: idle-boundary cache prune yielded " + yieldedBefore
                    + " passes in a row; this one runs to completion and builds wait for it");
        }
        PruneYield yield = new PruneYield(mustFinish ? this::safePointOnly : this::buildWaiting);
        BuildPlan plan = CachePlans.pruneBuildPlan(cache, false, ambient, yield);
        BuildPlanResult result = plan.run();
        if (yield.yielded()) {
            yieldStreak.set(new YieldStreak(cache, yieldedBefore + 1));
            pendingPruneCache.compareAndSet(null, cache);
            log.accept("jk engine: idle-boundary cache prune yielded to a build (" + (yieldedBefore + 1) + " of "
                    + MAX_YIELDED_PASSES + " in a row); it runs again at the next boundary");
            return;
        }
        yieldStreak.set(null);
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

    /** Whether a build waits on the gate this prune holds, or was admitted and soon will. */
    private boolean buildWaiting() {
        pruneSafePoint.run();
        return cacheGate.hasQueuedThreads() || activeBuildPlans.get() != 0;
    }

    /** A pass that must finish still runs the test seam at its safe points, and never stops at one. */
    private boolean safePointOnly() {
        pruneSafePoint.run();
        return false;
    }

    /** Test seam: {@code hook} runs at each safe point of the idle prune. */
    void onPruneSafePoint(Runnable hook) {
        pruneSafePoint = hook;
    }
}
