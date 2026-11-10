// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.cache.StoreCasGc;
import cc.jumpkick.config.DiskGuard;
import cc.jumpkick.config.DiskPressure;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.util.AccessLedger;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * The engine's two disk threads.
 *
 * <p>The <em>guard</em>, at high priority, checks the volumes jk writes to every {@link #POLL} and
 * whenever a write was refused ({@link DiskGuard#refused}). On a critical volume it collects the
 * artifact store at emergency windows on the spot, which is safe beside a build because each blob is
 * independent and none younger than an hour is touched, and asks for a cache prune now: it runs at
 * once when no build is live, else at the build boundary, while the guard keeps heavy blobs out of
 * the caches in between.
 *
 * <p>The <em>collector</em>, at low priority, runs the routine prune every {@link #ROUTINE} when its
 * cadence calls for one and no build is live; a build that arrives makes it yield at its next safe
 * point. On Linux the JVM maps no thread priority to the scheduler without privileges, so there the
 * yield is what keeps it out of a build's way.
 */
final class DiskWatch implements AutoCloseable {

    /** How often the guard looks, short of being woken. */
    static final Duration POLL = Duration.ofSeconds(15);

    /** The least time between two emergency passes, so a volume that stays critical is not walked in a loop. */
    static final Duration COOLDOWN = Duration.ofMinutes(1);

    /** How often the collector asks whether a routine prune is due. */
    static final Duration ROUTINE = Duration.ofHours(1);

    /** What the guard asks of the engine when a volume is critical. */
    interface Responder {
        /** Collect the artifact store at emergency windows. */
        void collectStore();

        /** Prune {@code cache} now, or at the build boundary; true when it ran now. */
        boolean pruneNow(Path cache);
    }

    private final List<Path> volumes;
    private final Function<Path, JkCacheConfig.@Nullable DiskSpace> probe;
    private final Responder responder;
    private final Consumer<String> log;
    private final LongSupplier clock;
    private final Semaphore wake = new Semaphore(0);
    private final Runnable listener = this::wake;
    private volatile boolean closed;
    private volatile long lastEmergency = Long.MIN_VALUE / 2;
    private volatile boolean wasCritical;
    private @Nullable Thread guard;
    private @Nullable Thread collector;

    DiskWatch(
            List<Path> volumes,
            Function<Path, JkCacheConfig.@Nullable DiskSpace> probe,
            Responder responder,
            Consumer<String> log,
            LongSupplier clock) {
        this.volumes = List.copyOf(volumes);
        this.probe = probe;
        this.responder = responder;
        this.log = log;
        this.clock = clock;
    }

    /** The watch over the engine's cache, store and state volumes, both threads started. */
    static DiskWatch start(IdleHousekeeping idle, Consumer<String> log) {
        Responder responder = new Responder() {
            @Override
            public void collectStore() {
                emergencyStoreGc(log);
            }

            @Override
            public boolean pruneNow(Path cache) {
                return idle.pruneNow(cache);
            }
        };
        DiskWatch watch = new DiskWatch(
                List.of(JkDirs.cache(), JkStores.store(), JkDirs.state()),
                JkCacheConfig.DiskSpace::probe,
                responder,
                log,
                Clock.SYSTEM::millis);
        watch.startThreads(cache -> idle.routinePrune(cache));
        return watch;
    }

    void startThreads(Consumer<Path> routine) {
        DiskGuard.onRefused(listener);
        // Engine-lifetime chores: they read no request's session.
        guard = Thread.ofPlatform()
                .name("jk-disk-guard")
                .daemon()
                .priority(Thread.MAX_PRIORITY)
                .start(this::guardLoop);
        collector = Thread.ofPlatform()
                .name("jk-cache-gc")
                .daemon()
                .priority(Thread.MIN_PRIORITY)
                .start(() -> collectorLoop(routine));
    }

    /** A refused write: look now rather than at the next poll. */
    void wake() {
        wake.release();
    }

    private void guardLoop() {
        while (!closed) {
            try {
                check();
                wake.tryAcquire(POLL.toMillis(), TimeUnit.MILLISECONDS);
                wake.drainPermits();
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                // the guard must outlive any one bad probe or pass
                Log.debug("disk guard: check failed", e);
            }
        }
    }

    private void collectorLoop(Consumer<Path> routine) {
        while (!closed) {
            try {
                Thread.sleep(ROUTINE.toMillis());
                if (!closed) routine.accept(JkDirs.cache());
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                Log.debug("cache gc: routine prune failed", e);
            }
        }
    }

    /**
     * One look at every volume: when one is critical and the last emergency pass is older than
     * {@link #COOLDOWN}, collect the store and prune the cache. Answers whether a volume was critical.
     */
    boolean check() {
        Path critical = null;
        JkCacheConfig.DiskSpace space = null;
        for (Path volume : volumes) {
            JkCacheConfig.DiskSpace s = probe.apply(volume);
            if (DiskGuard.critical(s)) {
                critical = volume;
                space = s;
                break;
            }
        }
        if (critical == null) {
            if (wasCritical) log.accept("jk engine: disk space recovered; caches write heavy blobs again");
            wasCritical = false;
            return false;
        }
        long now = clock.getAsLong();
        if (!wasCritical) {
            log.accept("jk engine: disk nearly full at " + critical + " (" + DiskGuard.describe(space)
                    + "); heavy blobs stay out of the caches and jk is reclaiming space");
        }
        wasCritical = true;
        if (now - lastEmergency < COOLDOWN.toMillis()) return true;
        lastEmergency = now;
        responder.collectStore();
        boolean ran = responder.pruneNow(JkDirs.cache());
        log.accept("jk engine: emergency reclaim " + (ran ? "ran" : "queued for the build boundary"));
        return true;
    }

    /** The artifact store collected at emergency windows: what the guard can do beside a live build. */
    static void emergencyStoreGc(Consumer<String> log) {
        try {
            Path storeRoot = JkStores.store();
            JkCacheConfig.DiskSpace space = JkCacheConfig.DiskSpace.probe(storeRoot);
            StoreCasGc.Policy policy =
                    StoreCasGc.Policy.defaults(DiskPressure.EMERGENCY).withBudget(DiskPressure.budgetBytes(space));
            AccessLedger ledger = AccessLedger.at(storeRoot.resolve(".access"));
            StoreCasGc.Report r = StoreCasGc.run(
                    JkStores.storeCas(), StoreCasGc.pinnedShas(), ledger.load(), policy, Clock.SYSTEM.millis(), false);
            if (r.deleted() > 0) {
                log.accept("jk engine: emergency store reclaim removed " + r.deleted() + " blobs ("
                        + (r.freedBytes() >> 20) + " MiB)");
            }
        } catch (Exception e) {
            Log.debug("emergency store reclaim failed", e);
        }
    }

    @Override
    public void close() {
        closed = true;
        DiskGuard.removeListener(listener);
        for (Thread t : new Thread[] {guard, collector}) {
            if (t != null) t.interrupt();
        }
    }

    /** Test seam: a predicate over the probe, for tests that drive {@link #check} directly. */
    static Function<Path, JkCacheConfig.@Nullable DiskSpace> constant(Predicate<Path> critical) {
        return p -> critical.test(p)
                ? new JkCacheConfig.DiskSpace(100L << 30, 1L << 30)
                : new JkCacheConfig.DiskSpace(100L << 30, 50L << 30);
    }
}
