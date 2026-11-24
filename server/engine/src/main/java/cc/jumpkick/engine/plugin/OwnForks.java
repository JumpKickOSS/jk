// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

/**
 * The room a waiting lease's own request holds in working forks, residents aside. Its build frees
 * that room as those forks end, so a line held up by it is not standing still, and giving up would
 * fail the very build that holds the memory, as when one module's test JVM waits behind a sibling
 * module's suite that runs for half an hour.
 */
final class OwnForks {

    private OwnForks() {}

    /**
     * Whether {@code waiter} would fit were the working forks of its own request in {@code held}
     * gone: a JVM slot, room under the heap ceiling and the bytes. {@code freeBytes}, {@code
     * freeSlots} and {@code heapRoom} are what the ledger has left of each with every holder counted,
     * negative when it is over. Called under the ledger's lock.
     */
    static boolean holdRoomFor(
            LeaseWaiter waiter, Iterable<LeaseWaiter> held, long freeBytes, int freeSlots, long heapRoom) {
        Long request = waiter.requestId;
        if (request == null) return false;
        long bytes = 0;
        long heaps = 0;
        int slots = 0;
        for (LeaseWaiter w : held) {
            if (w.resident != null || !request.equals(w.requestId)) continue;
            bytes += w.demand.bytes() + w.excess();
            heaps += w.demand.worstBytes();
            if (w.demand.cpuSlot()) slots++;
        }
        if (bytes == 0) return false;
        if (waiter.demand.cpuSlot() && freeSlots + slots <= 0) return false;
        if (heapRoom + heaps < waiter.demand.worstBytes()) return false;
        return waiter.demand.bytes() <= freeBytes + bytes;
    }
}
