// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolve.testing;

import cc.jumpkick.config.testing.BoundedGlobal;
import cc.jumpkick.resolve.ResolveProcessCacheControl;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Every process-wide resolve memo. Restore-only: fixture repositories reuse one coordinate with
 * different bytes, so the memos are dropped rather than put back.
 */
public final class ResolveProcessCacheGlobal implements BoundedGlobal {

    @Override
    public String name() {
        return "resolve-process-memos";
    }

    @Override
    public Optional<Object> capture() {
        return Optional.empty();
    }

    @Override
    public void restore(@Nullable Object captured) {
        ResolveProcessCacheControl.clearAll();
    }
}
