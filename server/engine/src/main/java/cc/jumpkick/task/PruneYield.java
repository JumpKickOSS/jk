// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import java.util.function.BooleanSupplier;

/**
 * Where a cache prune may stop because a build is waiting for the cache gate it holds. The prune
 * calls {@link #check} only at points where stopping leaves the cache consistent: between phases,
 * between files of a read-only scan, between single unreferenced blobs of the sweep, and before
 * each action entry the evictor takes (a key goes together with the blobs only it names, so an
 * entry is never split). A check that finds a build waiting unwinds the pass with {@link Yielded};
 * the caller sees {@link #yielded()} and runs the whole pass again later, because every root set
 * and refcount it computed is stale the moment it lets a build in.
 */
public final class PruneYield {

    /** Never stops: maintenance someone asked for in the foreground runs to the end. */
    public static final PruneYield NEVER = new PruneYield(() -> false);

    private final BooleanSupplier wanted;
    private volatile boolean yielded;

    /** @param wanted whether a build is waiting now; asked at every safe point, so it must be cheap */
    public PruneYield(BooleanSupplier wanted) {
        this.wanted = wanted;
    }

    /** A safe point: unwinds the pass with {@link Yielded} when a build is waiting. */
    public void check() {
        if (!wanted.getAsBoolean()) return;
        yielded = true;
        throw new Yielded();
    }

    /** Whether a {@link #check} stopped the pass. */
    public boolean yielded() {
        return yielded;
    }

    /** Unwinds a pass from the safe point that saw a build waiting. Carries no stack: it is not a fault. */
    public static final class Yielded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Yielded() {
            super("cache prune yielded to a waiting build", null, false, false);
        }
    }
}
