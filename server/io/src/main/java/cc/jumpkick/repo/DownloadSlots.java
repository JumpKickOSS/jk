// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.config.AvailableCpus;
import cc.jumpkick.http.HostRateLimiter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Process-wide bounds on what the engine has in flight against repositories, so a lock of several
 * hundred rows over several repositories, or a solver warm-up reading hundreds of POM chains, runs
 * a bounded number of them at once instead of one task per row, per repository leg, per BOM import.
 * Per-host politeness is {@link HostRateLimiter}'s; these caps are about what the engine holds:
 * connections, copy buffers, parsed bodies and parked legs, budgeted per slot.
 *
 * <p>Two kinds of slot:
 *
 * <ul>
 *   <li><b>row slots</b> ({@link #acquire()}), {@link #width()} of them — a lock row or sync row
 *       being assembled: its per-repository probes, download and sidecar reads. Held for the row.
 *   <li><b>leg slots</b> ({@link #acquireLeg(String)}), a pool per repository host of {@value
 *       #LEGS_PER_PERMIT} times the host's request permits — one repository asked over the network
 *       for one coordinate: a POM, a version catalog, an artifact. Taken by the thread that fans a
 *       fetch out across repositories, before each leg is handed to the io pool, and released as the
 *       leg ends, so what is parked waiting for a repository is bounded per host as well as what is
 *       connected, while a host whose queue is full holds back only the legs bound for it. A row
 *       holds a row slot while its legs take leg slots; a leg never waits on a row, so the two cannot
 *       deadlock each other.
 * </ul>
 *
 * <p>{@link #width()} is four slots per core, one per 4 MiB of maximum heap, whichever is smaller,
 * within [{@value #MIN_WIDTH}, {@value #MAX_WIDTH}]: a 24-core engine on the default 256 MiB heap
 * runs about sixty rows at once, enough to keep every host's permits busy.
 */
public final class DownloadSlots {

    static final int MIN_WIDTH = 8;
    static final int MAX_WIDTH = 64;
    private static final long HEAP_PER_SLOT = 4L << 20;
    private static final int SLOTS_PER_CORE = 4;

    /** Leg slots per request permit of a host: enough queued legs to keep its permits busy. */
    static final int LEGS_PER_PERMIT = 4;

    private static final int WIDTH =
            width(AvailableCpus.count(), Runtime.getRuntime().maxMemory());
    private static final Semaphore SLOTS = new Semaphore(WIDTH, true);
    private static final ConcurrentHashMap<String, Semaphore> LEGS = new ConcurrentHashMap<>();

    private DownloadSlots() {}

    /** The bound for {@code cores} and {@code maxHeapBytes}; see the class comment. */
    static int width(int cores, long maxHeapBytes) {
        long byHeap = maxHeapBytes <= 0 || maxHeapBytes == Long.MAX_VALUE ? MAX_WIDTH : maxHeapBytes / HEAP_PER_SLOT;
        long bound = Math.min((long) Math.max(1, cores) * SLOTS_PER_CORE, byHeap);
        return (int) Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, bound));
    }

    /** How many rows this engine materializes at once. */
    public static int width() {
        return WIDTH;
    }

    /** Take a row slot, waiting for one when every slot is busy; release it with {@link #release()}. */
    public static void acquire() throws InterruptedException {
        SLOTS.acquire();
    }

    /** Give a row slot back. */
    public static void release() {
        SLOTS.release();
    }

    /** Leg slots for a host with {@code permits} concurrent requests. */
    static int legWidth(int permits) {
        return Math.max(1, permits) * LEGS_PER_PERMIT;
    }

    /** Leg slots for {@code host}: {@value #LEGS_PER_PERMIT} times its {@link HostRateLimiter} permits. */
    public static int legWidth(@Nullable String host) {
        return legWidth(HostRateLimiter.shared().permitsFor(host));
    }

    private static Semaphore legs(@Nullable String host) {
        String key = host == null ? "" : host;
        return LEGS.computeIfAbsent(key, h -> new Semaphore(legWidth(h.isEmpty() ? null : h), true));
    }

    /** Take a leg slot for {@code host}, waiting when its queue is full; release it with {@link #releaseLeg(String)}. */
    public static void acquireLeg(@Nullable String host) throws InterruptedException {
        legs(host).acquire();
    }

    /** Take a leg slot for {@code host} if one is free right now. */
    public static boolean tryAcquireLeg(@Nullable String host) {
        return legs(host).tryAcquire();
    }

    /** Take a leg slot for {@code host} if one frees within {@code millis}. */
    public static boolean tryAcquireLeg(@Nullable String host, long millis) throws InterruptedException {
        return legs(host).tryAcquire(millis, TimeUnit.MILLISECONDS);
    }

    /** Give a leg slot for {@code host} back. */
    public static void releaseLeg(@Nullable String host) {
        legs(host).release();
    }

    /** Row slots not in use; for tests. */
    static int available() {
        return SLOTS.availablePermits();
    }

    /** Leg slots for {@code host} not in use; for tests. */
    static int legsAvailable(@Nullable String host) {
        return legs(host).availablePermits();
    }
}
