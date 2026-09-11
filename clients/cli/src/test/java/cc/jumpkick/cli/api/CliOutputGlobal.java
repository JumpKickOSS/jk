// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import cc.jumpkick.config.testing.BoundedGlobal;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@link CliOutput}'s per-command statics — script mode and the blank-line envelope flags.
 *
 * <p>Lives in {@code cc.jumpkick.cli} because {@link CliOutput#captureState()} / {@link
 * CliOutput#restoreState} are package-private. Bounded but not attributed: every dispatched command
 * writes these via {@link CliOutput#beginCommand}, so naming each writer is the same noise
 * {@code session} already declined.
 */
public final class CliOutputGlobal implements BoundedGlobal {

    @Override
    public String name() {
        return "cli-output";
    }

    @Override
    public boolean attributable() {
        return false;
    }

    @Override
    public Optional<Object> capture() {
        return Optional.of(CliOutput.captureState());
    }

    @Override
    public void restore(@Nullable Object captured) {
        if (captured instanceof CliOutput.State s) CliOutput.restoreState(s);
    }
}
