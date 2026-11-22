// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.host.Log;
import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import java.util.ArrayList;
import java.util.List;

/**
 * One line per heap retry in the current step, drained into the run's warnings so the results and
 * the agent report both carry it. The build stays green; the line says what was retried.
 *
 * <p>Notes are a {@link StepScope.Local} of the step that retried, and a step belongs to one module,
 * so in a parallel workspace build each module drains only its own retries, and a step that never
 * drains leaves nothing behind.
 */
public final class HeapNotes {

    /** Diagnostic code. The agent report prints warnings with this code even when the run is OK. */
    public static final String CODE = "heap-retry";

    private static final StepScope.Local<List<String>> NOTES = new StepScope.Local<>(ArrayList::new);

    private HeapNotes() {}

    /**
     * {@code retried with 1.0 GiB heap after running out of 512 MiB}, or the same shape after a
     * memory kill when {@code killed} is set.
     */
    public static String line(long nextBytes, long failedBytes, boolean killed) {
        String next = WorkerLeases.format(nextBytes);
        if (killed) return "retried with " + next + " heap after the worker was killed for memory";
        return "retried with " + next + " heap after running out of " + WorkerLeases.format(failedBytes);
    }

    /** Remember {@code text} for this thread's step. Outside a step it reaches the engine log only. */
    public static void note(String text) {
        if (text == null || text.isBlank()) return;
        List<String> notes = StepScope.local(NOTES);
        if (notes == null) {
            Log.info("jk engine: " + text);
            return;
        }
        synchronized (notes) {
            notes.add(text);
        }
    }

    /** The lines noted for this thread's step since the last drain, in order; none outside a step. */
    public static List<String> drain() {
        List<String> notes = StepScope.local(NOTES);
        if (notes == null) return List.of();
        synchronized (notes) {
            List<String> out = List.copyOf(notes);
            notes.clear();
            return out;
        }
    }

    /** Drain this thread's step's lines into {@code ctx} as {@link #CODE} warnings. Never fails the step. */
    public static void flush(TaskContext ctx) {
        if (ctx == null) return;
        for (String line : drain()) ctx.warn(CODE, line);
    }
}
