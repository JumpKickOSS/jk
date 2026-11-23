// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import java.util.List;

/**
 * What a lease asks of a {@link WorkerLeases.Ledger}. {@code cpuSlot}: counts against the running-JVM cap; a resident helper between requests does
 * not. {@code worstBytes} is what the process may use: its {@code -Xmx} lease when {@code bytes} is
 * the lower price it is expected to hold or the budget a pin was cut to, else {@code bytes}. {@code pin} names that pin, {@code ""} otherwise.
 */
record LeaseDemand(
        long bytes,
        long xmxBytes,
        boolean jvm,
        boolean clamped,
        boolean userPinned,
        boolean overBudget,
        boolean cpuSlot,
        long worstBytes,
        String pin) {
    LeaseDemand(long bytes, long xmxBytes, boolean jvm, boolean clamped, boolean userPinned, boolean overBudget) {
        this(bytes, xmxBytes, jvm, clamped, userPinned, overBudget, jvm, bytes, "");
    }

    /**
     * What {@code command} leases against {@code capacityBytes}: a tool {@link
     * WorkerLeases#TOOL_BYTES}; a JVM its {@linkplain WorkerLeases#jvmLease lease}, with a planned
     * {@code -Xmx} lowered to fit and a user's pin kept but charged at most the whole capacity. A
     * {@code choice} that prices the fork below its ceiling charges that price.
     */
    static LeaseDemand of(List<String> command, JvmOptions.HeapChoice choice, long capacityBytes) {
        long cap = Math.max(1, capacityBytes);
        long requested = HeapFlags.parseXmx(command);
        if (requested <= 0 && choice.xmxBytes() > 0) requested = choice.xmxBytes();
        boolean jvm = requested > 0 || HeapFlags.jvmCommand(command);
        if (!jvm) {
            long bytes = Math.min(WorkerLeases.TOOL_BYTES, cap);
            return new LeaseDemand(bytes, 0, false, bytes < WorkerLeases.TOOL_BYTES, false, false);
        }
        long xmx = requested > 0 ? requested : UnsizedHeap.lease(command, cap);
        if (choice.userPinned()) {
            long natural = WorkerLeases.jvmLease(xmx);
            long bytes = Math.min(cap, natural);
            String pin = choice.label().isEmpty() ? "heap" : choice.label();
            return new LeaseDemand(
                    bytes, requested > 0 ? requested : 0, true, false, true, natural > cap, true, natural, pin);
        }
        long fitted = WorkerLeases.clampXmx(xmx, cap);
        long ceiling = Math.min(cap, WorkerLeases.jvmLease(fitted));
        long bytes = choice.priceBytes() > 0 ? Math.min(ceiling, choice.priceBytes()) : ceiling;
        return new LeaseDemand(
                bytes,
                fitted,
                true,
                fitted < xmx || ceiling < WorkerLeases.jvmLease(xmx),
                false,
                false,
                true,
                ceiling,
                "");
    }

    /** The same demand without a JVM slot: a resident helper, or a JVM whose work runs in forks leased elsewhere. */
    LeaseDemand withoutSlot() {
        return new LeaseDemand(bytes, xmxBytes, jvm, clamped, userPinned, overBudget, false, worstBytes, pin);
    }

    /**
     * The same demand for a JVM that runs engines of its own: it leases {@code priceBytes} when that
     * is more than its own {@code -Xmx} lease, at most {@code capacityBytes}. Those engines' forks
     * run under it and lease from the inner engines' own ledger ({@link LeaseScope#NESTED}), which no
     * engine outside a worker reads, so what it holds is its whole process tree, not its heap. A
     * user pin keeps its lease.
     */
    LeaseDemand drivingEngines(long priceBytes, long capacityBytes) {
        if (userPinned || priceBytes <= bytes) return this;
        long next = Math.min(Math.max(1, capacityBytes), priceBytes);
        return new LeaseDemand(
                next, xmxBytes, jvm, clamped, false, overBudget, cpuSlot, Math.max(worstBytes, next), pin);
    }

    /** The same demand leasing {@code next} bytes. */
    LeaseDemand withBytes(long next) {
        return new LeaseDemand(next, xmxBytes, jvm, clamped, userPinned, overBudget, cpuSlot, next, pin);
    }
}
