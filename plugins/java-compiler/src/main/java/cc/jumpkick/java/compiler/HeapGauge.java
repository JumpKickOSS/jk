// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.java.compiler;

import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.management.NotificationEmitter;
import org.jspecify.annotations.Nullable;

/**
 * What the compiler worker tells the engine about its heap, so the engine can lease the worker by
 * what it holds rather than by the most it may grow to, and later decide how many compiles to run
 * at once. Each reading is one {@link PluginProtocol#HEAP} frame:
 *
 * <ul>
 *   <li>{@code used_after_gc} — the heap that survived each pool's most recent collection, the
 *       live set the compiles in flight hold;
 *   <li>{@code committed} and {@code max} — what the JVM has from the OS and the most it may take;
 *   <li>{@code gc_frac} — the share of wall time spent collecting since the previous reading;
 *   <li>{@code metaspace} — loaded classes, which processor class loaders grow;
 *   <li>{@code inflight} — the compiles running when the reading was taken.
 * </ul>
 */
final class HeapGauge {

    private final Clock clock;
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final List<MemoryPoolMXBean> heapPools = new ArrayList<>();
    private final @Nullable MemoryPoolMXBean metaspace;
    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private long lastGcMillis;
    private long lastWallNanos;

    HeapGauge(Clock clock) {
        this.clock = clock;
        MemoryPoolMXBean meta = null;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.getCollectionUsage() != null) heapPools.add(pool);
            if ("Metaspace".equals(pool.getName())) meta = pool;
        }
        this.metaspace = meta;
        this.lastGcMillis = gcMillis();
        this.lastWallNanos = clock.nanos();
    }

    /** The heap that survived each pool's most recent collection, summed over the heap pools. */
    long usedAfterGc() {
        long used = 0;
        for (MemoryPoolMXBean pool : heapPools) {
            MemoryUsage after = pool.getCollectionUsage();
            if (after != null) used += Math.max(0, after.getUsed());
        }
        return used;
    }

    /** One {@link PluginProtocol#HEAP} frame, with the GC share measured since the previous one. */
    synchronized String frame(int inflight) {
        MemoryUsage heap = memory.getHeapMemoryUsage();
        long gc = gcMillis();
        long now = clock.nanos();
        double wallMillis = Math.max(1.0, (now - lastWallNanos) / 1_000_000.0);
        double frac = Math.max(0.0, Math.min(1.0, (gc - lastGcMillis) / wallMillis));
        lastGcMillis = gc;
        lastWallNanos = now;
        MemoryPoolMXBean meta = metaspace;
        return JsonFields.object()
                .string(PluginProtocol.T, PluginProtocol.HEAP)
                .number(PluginProtocol.USED_AFTER_GC, usedAfterGc())
                .number("committed", heap.getCommitted())
                .number("max", heap.getMax())
                .token("gc_frac", String.format(Locale.ROOT, "%.4f", frac))
                .number("metaspace", meta == null ? 0L : meta.getUsage().getUsed())
                .number("inflight", inflight)
                .finish();
    }

    /** Run {@code onCollection} after every collection of the old generation (or a whole-heap one). */
    void onOldCollection(Runnable onCollection) {
        for (GarbageCollectorMXBean gc : collectors) {
            if (!(gc instanceof NotificationEmitter emitter) || !old(gc.getName())) continue;
            emitter.addNotificationListener((notification, handback) -> onCollection.run(), null, null);
        }
    }

    /** Whether {@code name} is a collector that reaches past the young generation. */
    static boolean old(String name) {
        return name.contains("Old") || name.contains("MarkSweep") || name.contains("Concurrent");
    }

    private long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean gc : collectors) total += Math.max(0, gc.getCollectionTime());
        return total;
    }
}
