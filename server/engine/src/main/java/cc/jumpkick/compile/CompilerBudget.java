// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.config.AvailableCpus;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * What every compiler pool of the engine shares: the compiles in flight across all of them, and the
 * heap each resident worker may grow to.
 *
 * <p>Each worker runs one compile per thread, and two pools — another JDK, a module with its own
 * {@code -J} flags — would each run one per core. So the compiles in flight across every pool stay
 * within the cores the engine sees ({@link AvailableCpus#count()}, which honours a cgroup quota).
 *
 * <p>A worker's heap ceiling is {@link #MAX_RAM_FRACTION} of RAM. When the engine contains each
 * worker in a cgroup of its own, that group's {@code memory.max} is the whole worker budget, and the
 * heap must leave {@link #NATIVE_HEADROOM_BYTES} under it for metaspace, code cache and thread
 * stacks, or the kernel kills a worker the JVM still thinks has room. Every live worker's ceiling
 * counts against the budget, so a second pool started while the first holds most of it gets a
 * smaller heap rather than a promise the host cannot keep.
 */
final class CompilerBudget {

    /** The share of RAM a lone compiler worker's heap may grow to. */
    static final double MAX_RAM_FRACTION = 0.68;

    /** What a contained worker keeps below its cgroup cap for everything that is not heap. */
    static final long NATIVE_HEADROOM_BYTES = 1536L << 20;

    /** The least heap a worker is started with, however little is left. */
    static final long LEAST_HEAP_BYTES = 512L << 20;

    private static final AtomicInteger INFLIGHT = new AtomicInteger();
    private static final Map<CompilerWorker, Long> CEILINGS = new ConcurrentHashMap<>();
    private static volatile int slotsForTests;

    private CompilerBudget() {}

    /**
     * How a worker is started: an explicit {@code -Xmx} or none (the JVM's own {@link
     * #MAX_RAM_FRACTION} of RAM), the ceiling that counts against the budget, and its cgroup cap
     * ({@code -1} when workers are not contained one by one).
     */
    record Heap(@Nullable Long xmxBytes, long ceilingBytes, long capBytes) {}

    /** Compiles every pool together may have in flight. */
    static int slots() {
        int pinned = slotsForTests;
        return pinned > 0 ? pinned : Math.max(1, AvailableCpus.count());
    }

    /** Whether one more compile may go into flight anywhere in the engine. */
    static boolean slotFree() {
        return INFLIGHT.get() < slots();
    }

    static void took() {
        INFLIGHT.incrementAndGet();
    }

    static void gave(int n) {
        if (n > 0) INFLIGHT.updateAndGet(v -> Math.max(0, v - n));
    }

    /** Compiles in flight across every pool. */
    static int inflight() {
        return INFLIGHT.get();
    }

    /** Test seam: pin {@link #slots()}; 0 reads the host again. */
    static void slotsForTests(int slots) {
        slotsForTests = Math.max(0, slots);
    }

    /**
     * Reserve {@code worker}'s heap against a budget of {@code budgetBytes} on a host with {@code
     * ramBytes}; {@code contained} says each worker gets its own cgroup capped at the budget. Held
     * until {@link #release}.
     */
    static Heap reserve(CompilerWorker worker, long ramBytes, long budgetBytes, boolean contained) {
        synchronized (CEILINGS) {
            long others = 0;
            for (Map.Entry<CompilerWorker, Long> e : CEILINGS.entrySet()) {
                if (e.getKey() != worker) others += e.getValue();
            }
            Heap heap = plan(ramBytes, budgetBytes, others, contained);
            CEILINGS.put(worker, heap.ceilingBytes());
            return heap;
        }
    }

    /** {@code worker} is gone: its ceiling no longer counts. */
    static void release(CompilerWorker worker) {
        CEILINGS.remove(worker);
    }

    /**
     * The heap for a new worker: {@link #MAX_RAM_FRACTION} of {@code ramBytes}, under a cgroup cap
     * of the whole budget less {@link #NATIVE_HEADROOM_BYTES} when {@code contained}, and within what
     * {@code otherCeilings} leave of the budget; never below {@link #LEAST_HEAP_BYTES}. The heap is
     * an explicit {@code -Xmx} whenever it is not simply the JVM's own share of RAM.
     */
    static Heap plan(long ramBytes, long budgetBytes, long otherCeilings, boolean contained) {
        long natural = (long) (Math.max(0, ramBytes) * MAX_RAM_FRACTION);
        long ceiling = natural;
        if (contained && budgetBytes > 0) ceiling = Math.min(ceiling, budgetBytes - NATIVE_HEADROOM_BYTES);
        if (budgetBytes > 0) ceiling = Math.min(ceiling, budgetBytes - Math.max(0, otherCeilings));
        ceiling = Math.max(LEAST_HEAP_BYTES, ceiling);
        boolean explicit = contained || ceiling < natural;
        long cap = contained && budgetBytes > 0 ? budgetBytes : -1L;
        return new Heap(explicit ? ceiling : null, ceiling, cap);
    }
}
