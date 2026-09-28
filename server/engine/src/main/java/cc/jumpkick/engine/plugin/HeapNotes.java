// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import cc.jumpkick.run.TaskContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One line per heap retry in the current job, drained into the run's warnings so the results and
 * the agent report both carry it. The build stays green; the line says what was retried.
 */
public final class HeapNotes {

    /** Diagnostic code. The agent report prints warnings with this code even when the run is OK. */
    public static final String CODE = "heap-retry";

    private static final ConcurrentHashMap<Long, List<String>> BY_REQUEST = new ConcurrentHashMap<>();

    private static final ThreadLocal<List<String>> LOCAL = ThreadLocal.withInitial(ArrayList::new);

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

    /** Remember {@code text} for this job. A thread with no job scope keeps it on the thread. */
    public static void note(String text) {
        if (text == null || text.isBlank()) return;
        Long id = JobWorkers.currentRequestId();
        if (id != null) {
            BY_REQUEST.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(text);
            return;
        }
        LOCAL.get().add(text);
    }

    /** The lines noted since the last drain, in order. */
    public static List<String> drain() {
        Long id = JobWorkers.currentRequestId();
        if (id != null) {
            List<String> lines = BY_REQUEST.remove(id);
            return lines == null ? List.of() : List.copyOf(lines);
        }
        List<String> lines = LOCAL.get();
        LOCAL.remove();
        return List.copyOf(lines);
    }

    /** {@link #drain} into {@code ctx} as {@link #CODE} warnings. Never fails the step. */
    public static void flush(TaskContext ctx) {
        if (ctx == null) return;
        for (String line : drain()) ctx.warn(CODE, line);
    }

    /** Forget every note. Tests. */
    public static void clear() {
        BY_REQUEST.clear();
        LOCAL.remove();
    }
}
