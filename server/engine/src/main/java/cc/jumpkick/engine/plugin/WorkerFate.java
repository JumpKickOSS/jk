// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import org.jspecify.annotations.Nullable;

/**
 * Why a forked worker stopped, in one place: it ran out of its own {@code -Xmx}, the kernel killed
 * it for memory, or something else.
 *
 * <p>A jk-planned batch JVM is started with {@code -XX:+ExitOnOutOfMemoryError}, which exits
 * {@value #EXIT_ON_OUT_OF_MEMORY}. A test JVM is not, since a test may provoke and catch the error;
 * one that escapes a test is that test's failure. A worker that keeps running after the error — a
 * test, a compiler that reports it — still names {@code Java heap space} or {@code GC overhead
 * limit exceeded} in its output. {@link WorkerContainment#killedForMemory} is the cgroup signal and consumes one
 * {@code oom_kill}; callers that already observed it pass that boolean in.
 */
public final class WorkerFate {

    /** HotSpot's exit status for {@code -XX:+ExitOnOutOfMemoryError}. */
    public static final int EXIT_ON_OUT_OF_MEMORY = 3;

    private WorkerFate() {}

    /** The three ways a worker stop is read. */
    public enum Cause {
        /** The process ran out of the heap its own {@code -Xmx} allowed. */
        HEAP_EXHAUSTED,
        /** The kernel or the workers cgroup killed it. Not its {@code -Xmx}. */
        KILLED_FOR_MEMORY,
        /** Any other exit. */
        OTHER
    }

    /**
     * Classify {@code exit} and {@code output}. Probes {@link WorkerContainment#killedForMemory}
     * once.
     */
    public static Cause classify(int exit, @Nullable String output) {
        return classify(exit, output, WorkerContainment.killedForMemory(exit));
    }

    /**
     * As {@link #classify(int, String)} when the caller already knows {@code killedForMemory}
     * (the probe consumes one {@code oom_kill}).
     */
    public static Cause classify(int exit, @Nullable String output, boolean killedForMemory) {
        if (killedForMemory) return Cause.KILLED_FOR_MEMORY;
        if (heapExhausted(exit, output)) return Cause.HEAP_EXHAUSTED;
        return Cause.OTHER;
    }

    /** True when {@code exit} is {@link #EXIT_ON_OUT_OF_MEMORY} or {@code output} names an own-heap OOM. */
    public static boolean heapExhausted(int exit, @Nullable String output) {
        return exit == EXIT_ON_OUT_OF_MEMORY || mentionsHeap(output);
    }

    /**
     * True when {@code text} names {@code Java heap space} or {@code GC overhead limit exceeded}.
     * Metaspace and direct-buffer failures are not this.
     */
    public static boolean mentionsHeap(@Nullable String text) {
        if (text == null || text.isEmpty()) return false;
        return text.contains("java.lang.OutOfMemoryError: Java heap space")
                || text.contains("GC overhead limit exceeded");
    }
}
