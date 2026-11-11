// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.run.StepScope;
import cc.jumpkick.run.TaskContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;

/**
 * One line per heap retry in the current step, drained into the run's warnings so the results and
 * the agent report both carry it. The build stays green; the line says what was retried.
 *
 * <p>Notes are kept per request and per step, and a step belongs to one module, so in a parallel
 * workspace build each module drains only its own retries.
 */
public final class HeapNotes {

    /** Diagnostic code. The agent report prints warnings with this code even when the run is OK. */
    public static final String CODE = "heap-retry";

    private static final ConcurrentHashMap<Key, List<String>> BY_STEP = new ConcurrentHashMap<>();

    private static final ThreadLocal<List<String>> LOCAL = ThreadLocal.withInitial(ArrayList::new);

    static {
        // A step that never flushed leaves nothing behind once it ends.
        StepScope.onClose(step -> BY_STEP.keySet().removeIf(k -> k.step() == step));
    }

    /** A request's notes from one step; {@code step} is null for a note made outside any step. */
    private record Key(long request, @Nullable TaskContext step) {}

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

    /** Remember {@code text} for this thread's step. A thread with no job scope keeps it on the thread. */
    public static void note(String text) {
        if (text == null || text.isBlank()) return;
        Long id = JobWorkers.currentRequestId();
        if (id != null) {
            BY_STEP.computeIfAbsent(new Key(id, StepScope.current()), k -> new CopyOnWriteArrayList<>())
                    .add(text);
            return;
        }
        LOCAL.get().add(text);
    }

    /** The lines noted for this thread's step since the last drain, in order. */
    public static List<String> drain() {
        return drain(StepScope.current());
    }

    /** Drain the lines {@code ctx}'s step noted into it as {@link #CODE} warnings. Never fails the step. */
    public static void flush(TaskContext ctx) {
        if (ctx == null) return;
        for (String line : drain(ctx)) ctx.warn(CODE, line);
    }

    /** Forget every note. Tests. */
    public static void clear() {
        BY_STEP.clear();
        LOCAL.remove();
    }

    private static List<String> drain(@Nullable TaskContext step) {
        Long id = JobWorkers.currentRequestId();
        if (id != null) {
            List<String> lines = BY_STEP.remove(new Key(id, step));
            return lines == null ? List.of() : List.copyOf(lines);
        }
        List<String> lines = LOCAL.get();
        LOCAL.remove();
        return List.copyOf(lines);
    }
}
