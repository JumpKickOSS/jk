// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.engine.jobs.LastBuiltRoot;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryNotificationInfo;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;

/**
 * Drops the process-wide memos ({@link MemoTrim}) when the heap left after a collection passes
 * {@link #THRESHOLD} of its maximum, whether or not the engine is idle. The idle trims never run
 * while sessions keep jobs in flight back to back, and each memo's own bound is sized for one
 * workspace, so without this several workspaces' memos together could fill the capped heap. Every
 * memo dropped is an optimisation: the cost is recomputing it.
 */
final class HeapPressure implements AutoCloseable {

    /** The share of a heap pool's maximum still in use after a collection that counts as pressure. */
    static final double THRESHOLD = 0.75;

    /** The least time between two trims, so a heap whose live data stays high is not trimmed in a loop. */
    static final Duration COOLDOWN = Duration.ofSeconds(30);

    private final Supplier<String> trim;
    private final LongSupplier usedHeap;
    private final Consumer<String> log;
    private final LongSupplier clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final NotificationListener listener = this::notified;
    private volatile long lastTrim = Long.MIN_VALUE / 2;
    private volatile boolean closed;

    HeapPressure(Supplier<String> trim, LongSupplier usedHeap, Consumer<String> log, LongSupplier clock) {
        this.trim = trim;
        this.usedHeap = usedHeap;
        this.log = log;
        this.clock = clock;
    }

    /** Watch this JVM's heap; the trim keeps the manifests of the workspace built last. */
    static HeapPressure start(Consumer<String> log) {
        HeapPressure pressure = new HeapPressure(
                () -> MemoTrim.drop(LastBuiltRoot.get()), HeapPressure::usedHeap, log, Clock.SYSTEM::millis);
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            long max = pool.getUsage().getMax();
            if (pool.getType() == MemoryType.HEAP && pool.isCollectionUsageThresholdSupported() && max > 0) {
                pool.setCollectionUsageThreshold((long) (max * THRESHOLD));
            }
        }
        if (ManagementFactory.getMemoryMXBean() instanceof NotificationEmitter emitter) {
            emitter.addNotificationListener(pressure.listener, null, null);
        }
        return pressure;
    }

    private void notified(Notification n, Object handback) {
        if (MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED.equals(n.getType())) pressed();
    }

    /**
     * A collection left the heap over the threshold: trim on a thread of its own, unless a trim is
     * running or one ran within {@link #COOLDOWN}. Returns whether a trim started.
     */
    boolean pressed() {
        if (closed || clock.getAsLong() - lastTrim < COOLDOWN.toMillis()) return false;
        if (!running.compareAndSet(false, true)) return false;
        // Engine-lifetime chore: it reads no request's session.
        Thread.ofPlatform().name("jk-heap-pressure").daemon().start(this::trimNow);
        return true;
    }

    private void trimNow() {
        try {
            long before = usedHeap.getAsLong();
            String memos = trim.get();
            log.accept("jk engine: heap pressure at " + (before >> 20) + " MiB: " + memos);
        } catch (RuntimeException e) {
            Log.debug("heap pressure trim failed", e);
        } finally {
            lastTrim = clock.getAsLong();
            running.set(false);
        }
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    @Override
    public void close() {
        closed = true;
        if (ManagementFactory.getMemoryMXBean() instanceof NotificationEmitter emitter) {
            try {
                emitter.removeNotificationListener(listener);
            } catch (ListenerNotFoundException e) {
                Log.debug("heap pressure listener already gone", e);
            }
        }
    }
}
