// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import cc.jumpkick.config.testing.BoundedGlobal;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@link CodeFormatter}'s left-threads-behind flag: one run that abandons a thread to the per-file
 * timeout makes the formatter non-reusable for the rest of the process.
 */
public final class LeftThreadsBehindGlobal implements BoundedGlobal {

    @Override
    public String name() {
        return "formatter-left-threads-behind";
    }

    /** Bounded but not attributed: the timeout tests abandon threads by design. */
    @Override
    public boolean attributable() {
        return false;
    }

    @Override
    public Optional<Object> capture() {
        return Optional.of(!new CodeFormatter().reusable());
    }

    @Override
    public void restore(@Nullable Object captured) {
        if (captured instanceof Boolean left) CodeFormatter.restoreLeftThreadsBehind(left);
    }
}
