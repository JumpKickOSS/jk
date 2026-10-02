// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import java.util.List;

/**
 * The heap the ledger leases for a JVM command that names none. A native-image builder gets its
 * {@link NativeHeap#generous} heap, which jk then passes as {@code -J-Xmx}. Any other JVM is leased
 * what it likely uses — its learned peak sized as a planned heap when its {@link HeapScope} has one,
 * else {@link LearnedHeaps#firstHeap} — never more than its {@link #ramCeiling}. That JVM is not
 * given the heap, so it can still grow, and the ledger charges the larger of its lease and its
 * measured resident set.
 */
final class UnsizedHeap {

    /** The least a {@code java} command that names no heap is assumed to use. */
    static final long LEAST_BYTES = 512L << 20;

    private static final String MAX_RAM_PERCENTAGE = "-XX:MaxRAMPercentage=";

    private UnsizedHeap() {}

    /** The heap {@code command} is leased under a budget of {@code capacityBytes}. */
    static long lease(List<String> command, long capacityBytes) {
        if (WorkerLeases.nativeImageCommand(command)) return NativeHeap.generous(capacityBytes);
        HeapScope.Key key = HeapScope.get();
        long peak = key == null ? 0 : LearnedHeaps.engine().peak(key);
        long likely =
                peak > 0 ? LearnedHeaps.size(peak, capacityBytes) : LearnedHeaps.firstHeap(LEAST_BYTES, capacityBytes);
        return Math.max(WorkerLeases.MIN_XMX, Math.min(likely, ramCeiling(command)));
    }

    /**
     * The most heap a JVM that names none may take: its last {@code -XX:MaxRAMPercentage} (25%, the
     * JVM's default, when it names none) of the memory the host reports.
     */
    static long ramCeiling(List<String> command) {
        double percent = 25.0;
        for (String arg : command) {
            String bare = arg.startsWith("-J") ? arg.substring(2) : arg;
            if (!bare.startsWith(MAX_RAM_PERCENTAGE)) continue;
            try {
                percent = Double.parseDouble(bare.substring(MAX_RAM_PERCENTAGE.length()));
            } catch (NumberFormatException e) {
                // a flag the JVM itself refuses; keep the default
            }
        }
        return (long) (MemoryProbe.current().totalBytes() * Math.max(0.0, Math.min(100.0, percent)) / 100.0);
    }
}
