// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.Log;
import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One memory-wait line per step. Grants add their memory waits of {@link
 * MemoryHolds#HOLD_AFTER_NANOS} or more; the step's scope closing writes the sum, so a step that
 * queued several forks names the wait once. A wait for a JVM slot is never one.
 */
public final class MemoryNotes {

    /** Diagnostic code. The results and the agent report print warnings with this code. */
    public static final String CODE = "memory-wait";

    /** The step's summed wait, written once when its scope closes. */
    private static final StepScope.Local<AtomicLong> NANOS = new StepScope.Local<>(AtomicLong::new);

    static {
        StepScope.onClose(MemoryNotes::flush);
    }

    private MemoryNotes() {}

    /** {@code waited 3s for memory}, or {@code waited 1.5s for memory}. */
    public static String waitedPhrase(long nanos) {
        double seconds = Math.max(0, nanos) / 1_000_000_000.0;
        String text = seconds >= 10
                ? String.format(Locale.ROOT, "%.0f", seconds)
                : String.format(Locale.ROOT, "%.1f", seconds);
        if (text.endsWith(".0")) text = text.substring(0, text.length() - 2);
        return "waited " + text + "s for memory";
    }

    /**
     * The engine log's line for a fork still waiting: a memory wait at info, a slot wait, which is
     * ordinary queueing, at debug. A step shows neither; its row says it waits ({@link StepWaits}).
     */
    static void logWaiting(WaitReason reason, long need, long free) {
        if (reason == WaitReason.SLOT) {
            Log.debug("jk engine: waiting for a free JVM slot");
            return;
        }
        Log.info("jk engine: waiting for memory: need " + WorkerLeases.format(need) + ", free "
                + WorkerLeases.format(Math.max(0, free)));
    }

    /**
     * A lease that queued was granted after {@code waitedNanos}, {@code memoryNanos} of them waiting
     * for memory and {@code heldNanos} with the build on hold.
     */
    static void noteGranted(long waitedNanos, long memoryNanos, long heldNanos) {
        if (memoryNanos > 0) Log.info("jk engine: " + waitedPhrase(memoryNanos));
        recordWait(memoryNanos, heldNanos);
    }

    /**
     * Count a granted fork's wait on this thread's step. The time on hold is the step's wait, kept out
     * of what it learns it costs; a memory wait of {@link MemoryHolds#HOLD_AFTER_NANOS} or more is
     * also a line on the step's output and one share of the results' single wait line. Outside a step,
     * nothing.
     */
    static void recordWait(long memoryNanos, long heldNanos) {
        TaskContext ctx = StepScope.current();
        AtomicLong waited = StepScope.local(NANOS);
        if (ctx == null || waited == null) return;
        if (heldNanos > 0) ctx.waited(Duration.ofNanos(heldNanos));
        if (memoryNanos < MemoryHolds.HOLD_AFTER_NANOS) return;
        ctx.output(waitedPhrase(memoryNanos));
        waited.addAndGet(memoryNanos);
    }

    /** One warning for the closing step {@code ctx} when it waited. */
    static void flush(TaskContext ctx) {
        AtomicLong held = StepScope.local(NANOS);
        if (held == null) return;
        long nanos = held.getAndSet(0);
        if (nanos <= 0) return;
        String phrase = waitedPhrase(nanos);
        if (WorkerContainment.leaseBudget().source() == WorkerContainment.BudgetSource.OVERRIDE) {
            phrase = phrase + " (" + WorkerContainment.BUDGET_ENV + ")";
        }
        ctx.warn(CODE, phrase);
    }
}
