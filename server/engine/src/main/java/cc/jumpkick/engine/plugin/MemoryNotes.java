// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.Log;
import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One memory-wait line per step. Grants add their wait; the step's scope closing writes the sum,
 * so a step that queued several forks names the wait once.
 */
public final class MemoryNotes {

    /** Diagnostic code. The results and the agent report print warnings with this code. */
    public static final String CODE = "memory-wait";

    /** A finished wait shorter than this is recorded and not printed. */
    private static final long OUTPUT_AFTER_NANOS = 500_000_000L;

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

    /** The line a waiting fork shows: {@code waiting for memory: need 512 MiB, free 128 MiB}. */
    public static String waitingLine(long needBytes, long freeBytes) {
        return "waiting for memory: need " + WorkerLeases.format(needBytes) + ", free "
                + WorkerLeases.format(Math.max(0, freeBytes));
    }

    /** The step's output carries the wait; its label stays the step's own ("compiling", …). */
    static void reportWaiting(long need, long free) {
        String line = waitingLine(need, free);
        Log.info("jk engine: " + line);
        TaskContext ctx = StepScope.current();
        if (ctx != null) ctx.output(line);
    }

    /** A lease that queued was granted after {@code waitedNanos}. */
    static void noteGranted(long waitedNanos) {
        if (waitedNanos <= 0) return;
        Log.info("jk engine: " + waitedPhrase(waitedNanos));
        recordWait(waitedNanos);
    }

    /**
     * Count {@code waitedNanos} on this thread's step. Half a second or more is also a line on the
     * step's output and one share of the results' single wait line. Outside a step, nothing.
     */
    static void recordWait(long waitedNanos) {
        TaskContext ctx = StepScope.current();
        AtomicLong held = StepScope.local(NANOS);
        if (ctx == null || held == null || waitedNanos <= 0) return;
        ctx.waited(Duration.ofNanos(waitedNanos));
        if (waitedNanos < OUTPUT_AFTER_NANOS) return;
        ctx.output(waitedPhrase(waitedNanos));
        held.addAndGet(waitedNanos);
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
