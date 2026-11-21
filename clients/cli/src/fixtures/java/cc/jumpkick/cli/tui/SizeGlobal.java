// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import cc.jumpkick.config.testing.BoundedGlobal;
import cc.jumpkick.terminal.Size;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * {@code Size.probe} plus the window it memoizes.
 *
 * <p>The probe is a public mutable static that tests swap to pin a terminal size; the memo behind it
 * is private. So the probe is captured and put back (attributable — a test that swaps it and forgets
 * is named), and the memo is dropped either way so the next caller re-derives through whichever
 * probe is now in place. Restoring the probe without dropping the memo would leave the previous
 * test's dimensions answering for it.
 */
public final class SizeGlobal implements BoundedGlobal {

    @Override
    public String name() {
        return "terminal-size";
    }

    @Override
    public Optional<Object> capture() {
        return Optional.of(Size.probe);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restore(@Nullable Object captured) {
        if (captured instanceof Supplier<?> s) Size.probe = (Supplier<Size.Window>) s;
        Size.reset();
    }
}
