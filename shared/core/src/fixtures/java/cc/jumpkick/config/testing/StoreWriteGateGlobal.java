// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config.testing;

import cc.jumpkick.util.StoreWriteGate;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@link StoreWriteGate}'s wiped flag: one store wipe switches off every background store writer
 * for the rest of the process, so a test that wipes would stand down the feed refreshes, warmups and
 * template freshens of every class after it in the same JVM.
 */
public final class StoreWriteGateGlobal implements BoundedGlobal {

    @Override
    public String name() {
        return "store-wiped";
    }

    /** Bounded but not attributed: the store-wipe tests set it by design. */
    @Override
    public boolean attributable() {
        return false;
    }

    @Override
    public Optional<Object> capture() {
        return Optional.of(StoreWriteGate.wipedSinceStart());
    }

    @Override
    public void restore(@Nullable Object captured) {
        if (Boolean.FALSE.equals(captured)) StoreWriteGate.resetForTests();
    }
}
