// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One memory-wait line per step. Grants add their wait; the step's scope closing writes the sum,
 * so a step that queued several forks names the wait once.
 */
public final class MemoryNotes {

    /** Diagnostic code. The results and the agent report print warnings with this code. */
    public static final String CODE = "memory-wait";

    private static final ConcurrentHashMap<TaskContext, AtomicLong> NANOS = new ConcurrentHashMap<>();

    static {
        StepScope.onClose(MemoryNotes::flush);
    }

    private MemoryNotes() {}

    /** Add {@code nanos} to the step's memory wait. A null step is ignored. */
    public static void add(TaskContext ctx, long nanos) {
        if (ctx == null || nanos <= 0) return;
        NANOS.computeIfAbsent(ctx, k -> new AtomicLong()).addAndGet(nanos);
    }

    /** One warning for {@code ctx} when it waited, then forget it. A second close is a no-op. */
    static void flush(TaskContext ctx) {
        AtomicLong held = NANOS.remove(ctx);
        if (held == null) return;
        long nanos = held.get();
        if (nanos <= 0) return;
        String phrase = WorkerLeases.waitedPhrase(nanos);
        if (WorkerContainment.leaseBudget().source() == WorkerContainment.BudgetSource.OVERRIDE) {
            phrase = phrase + " (" + WorkerContainment.BUDGET_ENV + ")";
        }
        ctx.warn(CODE, phrase);
    }
}
