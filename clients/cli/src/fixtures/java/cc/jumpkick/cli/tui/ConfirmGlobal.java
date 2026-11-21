// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.config.testing.BoundedGlobal;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@link Prompt}'s assume-yes flag, a thread-local dispatch sets around one leaf command and clears
 * after it. A test that drives a command body directly and sets it itself must clear it: left on
 * the worker thread, every later {@code Confirm.ask()} in the same JVM answers yes without
 * printing, and the class that inherits it fails on an unrelated assertion. Attributable, so the
 * report names the class that left it set.
 */
public final class ConfirmGlobal implements BoundedGlobal {

    @Override
    public String name() {
        return "confirm-assume-yes";
    }

    @Override
    public Optional<Object> capture() {
        return Optional.of(Prompt.assumeYes());
    }

    @Override
    public void restore(@Nullable Object captured) {
        if (Boolean.TRUE.equals(captured)) {
            Prompt.setAssumeYes(true);
        } else {
            Prompt.clearAssumeYes();
        }
    }
}
