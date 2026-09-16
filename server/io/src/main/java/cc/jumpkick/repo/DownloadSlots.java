// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.config.AvailableCpus;
import java.util.concurrent.Semaphore;

/**
 * Process-wide bound on the artifacts in flight across every materialize fan-out — the lock's row
 * downloads, {@code jk sync}'s — so a lock of several hundred rows over several repositories runs
 * a bounded number of them at once instead of one task, its per-repository legs and their sidecar
 * reads for every row. Per-host politeness is {@link cc.jumpkick.http.HostRateLimiter}'s; this cap
 * is about what the engine holds: connections, copy buffers and parked legs, budgeted per slot.
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

    private static final int WIDTH =
            width(AvailableCpus.count(), Runtime.getRuntime().maxMemory());
    private static final Semaphore SLOTS = new Semaphore(WIDTH, true);

    private DownloadSlots() {}

    /** The bound for {@code cores} and {@code maxHeapBytes}; see the class comment. */
    static int width(int cores, long maxHeapBytes) {
        long byHeap = maxHeapBytes <= 0 || maxHeapBytes == Long.MAX_VALUE ? MAX_WIDTH : maxHeapBytes / HEAP_PER_SLOT;
        long bound = Math.min((long) Math.max(1, cores) * SLOTS_PER_CORE, byHeap);
        return (int) Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, bound));
    }

    /** How many artifacts this engine materializes at once. */
    public static int width() {
        return WIDTH;
    }

    /** Take a slot, waiting for one when every slot is busy; release it with {@link #release()}. */
    public static void acquire() throws InterruptedException {
        SLOTS.acquire();
    }

    /** Give a slot back. */
    public static void release() {
        SLOTS.release();
    }

    /** Slots not in use; for tests. */
    static int available() {
        return SLOTS.availablePermits();
    }
}
