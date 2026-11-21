// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

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

    LeaseDemand resident() {
        return new LeaseDemand(bytes, xmxBytes, jvm, clamped, userPinned, overBudget, false, worstBytes, pin);
    }

    /** The same demand leasing {@code next} bytes. */
    LeaseDemand withBytes(long next) {
        return new LeaseDemand(next, xmxBytes, jvm, clamped, userPinned, overBudget, cpuSlot, next, pin);
    }
}
