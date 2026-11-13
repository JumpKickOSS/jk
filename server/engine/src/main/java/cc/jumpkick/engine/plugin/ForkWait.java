// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.run.TaskContext;
import org.jspecify.annotations.Nullable;

/**
 * One queued fork's wait for its worker lease, told why it still waits after each look at the queue.
 * It shows the wait on the step's row ({@link StepWaits}), counts the fork toward its build's hold
 * once it has waited {@link MemoryHolds#HOLD_AFTER_NANOS} for memory without a break, and logs it
 * every {@link #REPORT_EVERY_NANOS}.
 */
final class ForkWait {

    private static final long REPORT_EVERY_NANOS = 2_000_000_000L;

    private final Object fork;
    private final @Nullable Long requestId;
    private final @Nullable TaskContext step;
    private final boolean resident;
    private final long heldAtArrival;
    private long lastTick;
    private long memorySince;
    private long memoryNanos;
    private long lastReport;
    private boolean holding;

    ForkWait(Object fork, @Nullable Long requestId, @Nullable TaskContext step, boolean resident, long nowNanos) {
        this.fork = fork;
        this.requestId = requestId;
        this.step = step;
        this.resident = resident;
        this.heldAtArrival = requestId == null ? 0 : MemoryHolds.totalHeldMillis(requestId);
        this.lastTick = nowNanos;
    }

    /** The fork still waits, for {@code reason} (null before the queue's head was first refused). */
    void waiting(@Nullable WaitReason reason, long needBytes, long freeBytes, long nowNanos) {
        tick(nowNanos);
        StepWaits.waiting(step, fork, reason);
        if (reason == WaitReason.MEMORY) {
            if (memorySince == 0) memorySince = nowNanos;
        } else {
            memorySince = 0;
            stopHolding();
        }
        if (!holding && !resident && memorySince != 0 && nowNanos - memorySince >= MemoryHolds.holdAfterNanos()) {
            holding = true;
            MemoryHolds.waiting(requestId, needBytes, freeBytes);
        }
        if (reason != null && (lastReport == 0 || nowNanos - lastReport >= REPORT_EVERY_NANOS)) {
            MemoryNotes.logWaiting(reason, needBytes, freeBytes);
            lastReport = nowNanos;
        }
    }

    /** The fork was granted at {@code nowNanos}. */
    void granted(long nowNanos) {
        tick(nowNanos);
    }

    /** The wait is over, granted or not: the step's row and the build's hold forget it. */
    void close() {
        StepWaits.waiting(step, fork, null);
        stopHolding();
    }

    /** Nanoseconds the fork waited for memory. */
    long memoryNanos() {
        return memoryNanos;
    }

    /** Nanoseconds of the wait its build spent on hold. */
    long heldNanos() {
        return requestId == null ? 0 : Math.max(0, MemoryHolds.totalHeldMillis(requestId) - heldAtArrival) * 1_000_000L;
    }

    /** A granted fork of {@code requestId} and {@code step} runs. */
    static void running(@Nullable Long requestId, @Nullable TaskContext step) {
        MemoryHolds.started(requestId);
        StepWaits.started(step);
    }

    /** A running fork of {@code requestId} and {@code step} released its lease. */
    static void exited(@Nullable Long requestId, @Nullable TaskContext step) {
        MemoryHolds.ended(requestId);
        StepWaits.ended(step);
    }

    private void tick(long nowNanos) {
        if (memorySince != 0) memoryNanos += nowNanos - lastTick;
        lastTick = nowNanos;
    }

    private void stopHolding() {
        if (!holding) return;
        holding = false;
        MemoryHolds.done(requestId);
    }
}
