// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The heaps a jk-planned worker is re-run at after it runs out of its own: each retry doubles the
 * last heap, clamped so the lease fits the worker budget and the heap stays under the ceiling its
 * kind is given, for at most {@value #MAX_RETRIES} retries. The ladder ends early when neither
 * bound lets the heap grow any further.
 */
public final class HeapLadder {

    /** Re-runs after heap exhaustion, beyond the first attempt. */
    public static final int MAX_RETRIES = 3;

    private HeapLadder() {}

    /**
     * Twice {@code heapBytes}, clamped so the lease fits {@code budgetBytes} and the heap is at most
     * {@code ceilingBytes}, in the whole MiB an {@code -Xmx} names. A bound of {@code 0} or less is
     * not known and clamps nothing.
     */
    public static long doubled(long heapBytes, long budgetBytes, long ceilingBytes) {
        long twice = Math.max(0, heapBytes) * 2L;
        if (budgetBytes > 0) twice = LeaseDemand.clampXmx(twice, budgetBytes);
        if (ceilingBytes > 0) twice = Math.min(twice, ceilingBytes / WorkerLeases.MIB * WorkerLeases.MIB);
        return twice;
    }

    /**
     * The heap to try after every heap in {@code ranOut} (oldest first) ran out, or {@code null} when
     * the retries are spent or neither the budget nor the ceiling gives more than the last one.
     * {@code budgetBytes} bounds the lease, {@code ceilingBytes} the heap itself.
     */
    public static @Nullable Long next(List<Long> ranOut, long budgetBytes, long ceilingBytes) {
        if (ranOut.isEmpty() || ranOut.size() > MAX_RETRIES) return null;
        long last = ranOut.getLast();
        if (last <= 0) return null;
        long bigger = doubled(last, budgetBytes, ceilingBytes);
        return bigger > last ? bigger : null;
    }

    /** {@link #next(List, long, long)} with no heap ceiling. */
    public static @Nullable Long next(List<Long> ranOut, long budgetBytes) {
        return next(ranOut, budgetBytes, 0);
    }

    /** {@link #next(List, long)} against the engine's worker budget. */
    public static @Nullable Long next(List<Long> ranOut) {
        return next(ranOut, WorkerLeases.engine().capacityBytes());
    }

    /**
     * {@code ran out of heap at 256 MiB, 512 MiB and 1.0 GiB}, naming the bound that ended the
     * ladder when one did before the retries were spent: {@code ceilingBytes}, a test JVM's quarter
     * of the host's RAM, when it is no more than the budget can lease, else the budget.
     */
    public static String ranOut(List<Long> ranOut, long budgetBytes, long ceilingBytes) {
        StringBuilder sb = new StringBuilder("ran out of heap at ");
        for (int i = 0; i < ranOut.size(); i++) {
            if (i > 0) sb.append(i == ranOut.size() - 1 ? " and " : ", ");
            sb.append(WorkerLeases.format(ranOut.get(i)));
        }
        if (ranOut.isEmpty() || ranOut.size() > MAX_RETRIES) return sb.toString();
        long last = ranOut.getLast();
        boolean ceiling = ceilingBytes > 0 && doubled(last, 0, ceilingBytes) <= doubled(last, budgetBytes, 0);
        sb.append(ceiling ? ", a quarter of this host's RAM" : ", the most the worker budget can lease");
        return sb.toString();
    }

    /** {@link #ranOut(List, long, long)} against the engine's worker budget, with no heap ceiling. */
    public static String ranOut(List<Long> ranOut) {
        return ranOut(ranOut, WorkerLeases.engine().capacityBytes(), 0);
    }
}
