// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.config.Request;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Build units running right now in one job — the denominator for a decision a task can only make
 * when it dispatches.
 *
 * <p>The workspace plans a test-worker share from the graph's widest point, which stops describing
 * the machine as soon as the graph narrows: the last module standing is sharing with nobody.
 * Free worker leases look like the same answer and are not — they are an instantaneous
 * reading of a resource that is about to be contended, so sizing from them let mid-build suites take
 * sixteen runners each and starve the compile lanes.
 *
 * <p>Counting units is the honest denominator: it is the number the plan-time share was dividing by,
 * read late instead of early.
 *
 * <p>Each job's {@link Request} carries its own count under {@link #SLOT}, because an engine serves
 * concurrent builds and a process-wide count would let one job size itself off another's width.
 * The count goes when the request does, so a unit that enters after its job ended counts nowhere
 * that outlives the job.
 */
public final class LiveUnits {

    /** The job's count; a request without one counts nothing. */
    public static final Request.Slot<LiveUnits> SLOT = new Request.Slot<>("live-units");

    private static final Lease NONE = () -> {};

    private final AtomicInteger running = new AtomicInteger();

    /** A count for a new job, starting at none. */
    public LiveUnits() {}

    /** A held slot; closing releases it. */
    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }

    /** Count this thread's unit as running until the lease closes. A no-op outside a job. */
    public static Lease enter() {
        LiveUnits units = current();
        if (units == null) return NONE;
        units.running.incrementAndGet();
        return units.running::decrementAndGet;
    }

    /**
     * Stop counting this thread's unit until the lease closes — for a wait the unit spends doing
     * nothing, such as parking at the serial-test gate. A no-op outside a job or when the job counts
     * nothing.
     */
    public static Lease stepOut() {
        LiveUnits units = current();
        if (units == null || units.running.get() <= 0) return NONE;
        units.running.decrementAndGet();
        return units.running::incrementAndGet;
    }

    /** Units running in this thread's job, including the caller's own; {@code 0} outside a job. */
    public static int running() {
        LiveUnits units = current();
        return units == null ? 0 : Math.max(0, units.running.get());
    }

    private static @Nullable LiveUnits current() {
        Request request = Request.current();
        return request == null ? null : request.get(SLOT);
    }
}
