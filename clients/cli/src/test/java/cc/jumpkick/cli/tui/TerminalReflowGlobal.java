// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.config.testing.BoundedGlobal;
import java.util.Optional;

/**
 * {@code TerminalReflow.cached} — whether the terminal rewraps on resize, memoized from ambient env.
 *
 * <p>Restore-only. The memo has {@code force(Boolean)} and {@code reset()} but no reader, so a test
 * that changes it cannot be named; dropping the memo makes the next caller re-derive, which is the
 * property that matters. This is the global that made `JkManagerTreeTest` assert whatever the
 * developer's terminal happened to do.
 */
public final class TerminalReflowGlobal implements BoundedGlobal {

    @Override
    public String name() {
        return "terminal-reflow";
    }

    @Override
    public Optional<Object> capture() {
        return Optional.empty();
    }

    @Override
    public void restore(Object captured) {
        TerminalReflow.reset();
    }
}
