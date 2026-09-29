// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The heaps a jk-planned worker is re-run at after it runs out of its own: each retry doubles the
 * last heap, clamped so the lease fits the worker budget, for at most {@value #MAX_RETRIES}
 * retries. The ladder ends early when the budget cannot grow the heap any further.
 */
public final class HeapLadder {

    /** Re-runs after heap exhaustion, beyond the first attempt. */
    public static final int MAX_RETRIES = 3;

    private HeapLadder() {}

    /** Twice {@code heapBytes}, clamped so the lease fits {@code budgetBytes}. */
    public static long doubled(long heapBytes, long budgetBytes) {
        long twice = Math.max(0, heapBytes) * 2L;
        if (budgetBytes <= 0) return twice;
        return WorkerLeases.clampXmx(twice, budgetBytes);
    }

    /**
     * The heap to try after every heap in {@code ranOut} (oldest first) ran out, or {@code null} when
     * the retries are spent or the budget cannot give more than the last one.
     */
    public static @Nullable Long next(List<Long> ranOut, long budgetBytes) {
        if (ranOut.isEmpty() || ranOut.size() > MAX_RETRIES) return null;
        long last = ranOut.getLast();
        if (last <= 0) return null;
        long bigger = doubled(last, budgetBytes);
        return bigger > last ? bigger : null;
    }

    /** {@link #next(List, long)} against the engine's worker budget. */
    public static @Nullable Long next(List<Long> ranOut) {
        return next(ranOut, WorkerLeases.engine().capacityBytes());
    }

    /**
     * {@code ran out of heap at 256 MiB, 512 MiB and 1.0 GiB}, naming the host's limit when the
     * budget, not the retry count, ended the ladder.
     */
    public static String ranOut(List<Long> ranOut) {
        StringBuilder sb = new StringBuilder("ran out of heap at ");
        for (int i = 0; i < ranOut.size(); i++) {
            if (i > 0) sb.append(i == ranOut.size() - 1 ? " and " : ", ");
            sb.append(WorkerLeases.format(ranOut.get(i)));
        }
        if (ranOut.size() <= MAX_RETRIES) sb.append(", the most this host can give one worker");
        return sb.toString();
    }
}
