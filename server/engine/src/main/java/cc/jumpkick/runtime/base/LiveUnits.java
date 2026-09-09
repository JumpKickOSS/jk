// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.engine.plugin.JobWorkers;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Build units running right now, per job — the denominator for a decision a task can only make when
 * it dispatches.
 *
 * <p>The workspace plans a test-worker share from the graph's widest point, which stops describing
 * the machine as soon as the graph narrows: the last module standing is sharing with nobody. Free
 * {@code PluginSlots} permits look like the same answer and are not — they are an instantaneous
 * reading of a resource that is about to be contended, so sizing from them let mid-build suites take
 * sixteen runners each and starve the compile lanes.
 *
 * <p>Counting units is the honest denominator: it is the number the plan-time share was dividing by,
 * read late instead of early.
 *
 * <p>Keyed by {@link JobWorkers} request, because an engine serves concurrent builds and a
 * process-wide count would let one job size itself off another's width.
 */
public final class LiveUnits {

    private static final ConcurrentMap<Long, AtomicInteger> BY_REQUEST = new ConcurrentHashMap<>();

    private LiveUnits() {}

    /** A held slot; closing releases it. */
    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }

    /** Count this thread's unit as running until the lease closes. A no-op outside a job scope. */
    public static Lease enter() {
        Long id = JobWorkers.currentRequestId();
        if (id == null) return () -> {};
        AtomicInteger running = BY_REQUEST.computeIfAbsent(id, k -> new AtomicInteger());
        running.incrementAndGet();
        return running::decrementAndGet;
    }

    /** Units running in this thread's job, including the caller's own; {@code 0} outside a job. */
    public static int running() {
        Long id = JobWorkers.currentRequestId();
        if (id == null) return 0;
        AtomicInteger running = BY_REQUEST.get(id);
        return running == null ? 0 : Math.max(0, running.get());
    }

    /** Drop the job's counter at teardown, alongside the other per-request state. */
    public static void end(long requestId) {
        BY_REQUEST.remove(requestId);
    }
}
