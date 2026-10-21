// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.compile.JavaCompilerHost.Lanes;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.engine.plugin.WarmLinger;
import cc.jumpkick.host.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Compiler lanes that outlive the job that started them, for the {@link WarmLinger linger} after
 * their last item. A one-file edit on a warm worker is a compile; on a fresh one it is a JVM start,
 * a Zinc warm-up and a cold javac, which on petclinic is 50 ms against 590 ms.
 *
 * <p>A warm pool is keyed by everything a running worker cannot be given — JDK, worker jar, the
 * environment it was started with, heap and the module's own JVM flags — and not by job. Each lane
 * runs under a {@link JobWorkers} scope of its own, never a job's, so job teardown leaves it be,
 * and a lane still compiling for a job that has ended is stopped alone: that job's caller is gone,
 * and its compile must not go on writing the module's classes under the next build's.
 *
 * <p>A lane still yields to the lease ledger and the lane budget exactly as a job's does: an idle
 * warm lane exits when a fork waits for its memory or another pool needs its slot. Scala and the
 * other compiler-classpath requests stay job-scoped; their compiler caches are not worth holding.
 * A linger of {@code 0} turns warm lanes off.
 */
final class WarmLanes {

    private static final ConcurrentHashMap<String, Warm> POOLS = new ConcurrentHashMap<>();

    /** One {@link JobWorkers} scope per warm lane, counting up from below every job and ephemeral id. */
    private static final AtomicLong SCOPES = new AtomicLong(Long.MIN_VALUE);

    private static final Clock CLOCK = Clock.SYSTEM;

    private static volatile boolean hookRegistered;

    private WarmLanes() {}

    /** How long a warm pool waits after its last item before its lanes exit. */
    static long lingerMillis() {
        return WarmLinger.millis();
    }

    /** Whether {@code req} is compiled on a warm pool rather than its job's. */
    static boolean lingers(ForkedJavac.Request req) {
        String scala = req.scalaVersion();
        return lingerMillis() > 0
                && (scala == null || scala.isBlank())
                && req.compilerClasspath().isEmpty();
    }

    /** A fresh scope for one warm lane's worker. */
    static long nextScope() {
        return SCOPES.getAndIncrement();
    }

    /**
     * The warm pool for {@code key}, built by {@code make} when there is none, with {@code item}
     * counted against it until the item settles: a pool with an item on it is never retired, so
     * an enqueue cannot land on a pool its reaper has just closed.
     */
    static Lanes lanes(String key, Supplier<Lanes> make, CompileWork item) {
        Warm[] held = new Warm[1];
        POOLS.compute(key, (k, w) -> {
            Warm pool = w != null ? w : new Warm(k, make.get());
            pool.items++;
            held[0] = pool;
            return pool;
        });
        Warm pool = held[0];
        item.compile.whenComplete((r, e) -> pool.settled());
        pool.armReaper();
        registerShutdownHook();
        return pool.lanes;
    }

    /** Whether the warm pool for {@code key} has a worker running now. */
    static boolean running(String key) {
        Warm w = POOLS.get(key);
        return w != null && w.lanes.liveLanes() > 0;
    }

    /** {@code job} has ended: stop every warm lane still working on one of its items. */
    static void abandon(long job) {
        for (Warm w : POOLS.values()) w.lanes.stopLanesWorking(t -> t.job == job);
    }

    /** Test seam: retire every warm pool now and wait for its lanes to exit. */
    static void closeAllForTests() {
        for (String key : List.copyOf(POOLS.keySet())) {
            Warm w = POOLS.remove(key);
            if (w != null) w.retire();
        }
    }

    /** Test seam: the scope of every live warm lane, across pools. */
    static List<Long> laneScopes() {
        List<Long> scopes = new ArrayList<>();
        for (Warm w : POOLS.values()) scopes.addAll(w.lanes.laneScopes());
        return scopes;
    }

    /** Test seam: warm pools alive now. */
    static int poolCount() {
        return POOLS.size();
    }

    /**
     * The lanes outlive every job, so nothing else would stop them if the engine exits inside a
     * linger. The workers would leave on their stdin closing; the hook does not wait for that.
     */
    private static void registerShutdownHook() {
        if (hookRegistered) return;
        synchronized (WarmLanes.class) {
            if (hookRegistered) return;
            hookRegistered = true;
            // Shutdown hook; reads no session.
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                for (Warm w : POOLS.values()) w.lanes.stopLanesWorking(t -> true);
                            },
                            "jk-javac-linger-shutdown"));
        }
    }

    /** One warm pool and its reaper. {@link #items} is guarded by {@link #POOLS}' compute on {@link #key}. */
    private static final class Warm {
        final String key;
        final Lanes lanes;
        int items;
        volatile long lastUsedNanos = CLOCK.nanos();
        private @Nullable Thread reaper;

        Warm(String key, Lanes lanes) {
            this.key = key;
            this.lanes = lanes;
        }

        void settled() {
            POOLS.computeIfPresent(key, (k, w) -> {
                if (w == this) {
                    items--;
                    lastUsedNanos = CLOCK.nanos();
                }
                return w;
            });
        }

        synchronized void armReaper() {
            if (reaper == null)
                reaper = Thread.ofVirtual().name("jk-javac-linger").start(this::reap);
        }

        /**
         * Sleep until the linger could be over and look again: an item settled in between moves the
         * deadline, and one still on the pool keeps it. Retiring is decided under the same compute
         * that counts items in, so no item is ever handed a pool that is closing.
         */
        private void reap() {
            while (true) {
                long linger = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, lingerMillis()));
                long wait = linger - (CLOCK.nanos() - lastUsedNanos);
                if (wait <= 0) {
                    boolean[] retired = {false};
                    POOLS.computeIfPresent(key, (k, w) -> {
                        if (w != this || items > 0) return w;
                        retired[0] = true;
                        return null;
                    });
                    if (retired[0] || POOLS.get(key) != this) {
                        if (retired[0]) retire();
                        return;
                    }
                    wait = linger;
                }
                try {
                    Thread.sleep(Duration.ofNanos(wait));
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        /** {@code DONE} to every lane; the workers exit and their leases go back. */
        void retire() {
            lanes.close();
        }
    }
}
