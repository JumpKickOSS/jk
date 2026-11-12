// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

/** Test seam: put a build on hold for memory, or take it off, from another package's tests. */
public final class MemoryHoldsSeam {

    private MemoryHoldsSeam() {}

    public static void hold(long requestId, long needBytes, long freeBytes) {
        MemoryHolds.waiting(requestId, needBytes, freeBytes);
    }

    public static void clear(long requestId) {
        while (MemoryHolds.onHold(requestId)) MemoryHolds.done(requestId);
    }
}
