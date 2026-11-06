// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.config.testing.BoundedGlobal;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@link EngineAotTrainer}'s latches: one in-process engine shutdown would otherwise stop every
 * later engine in the JVM from ever recording its startup cache.
 */
public final class EngineAotTrainerGlobal implements BoundedGlobal {

    @Override
    public String name() {
        return "engine-aot-trainer";
    }

    /** Bounded but not attributed: every in-process engine's shutdown sets it by design. */
    @Override
    public boolean attributable() {
        return false;
    }

    @Override
    public Optional<Object> capture() {
        return Optional.of(EngineAotTrainer.latches());
    }

    @Override
    public void restore(@Nullable Object captured) {
        if (captured instanceof EngineAotTrainer.Latches l) EngineAotTrainer.restore(l);
    }
}
