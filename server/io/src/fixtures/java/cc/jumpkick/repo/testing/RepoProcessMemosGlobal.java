// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo.testing;

import cc.jumpkick.config.testing.BoundedGlobal;
import cc.jumpkick.repo.RepoProcessMemos;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * This module's process-wide fetch memos. Restore-only: fixture repositories reuse one coordinate
 * with different bytes, so the memos are dropped rather than put back. The resolver's
 * {@code ResolveProcessCacheGlobal} drops these too, but this module's tests cannot take that jar:
 * the resolver depends on this module.
 */
public final class RepoProcessMemosGlobal implements BoundedGlobal {

    @Override
    public String name() {
        return "repo-process-memos";
    }

    @Override
    public Optional<Object> capture() {
        return Optional.empty();
    }

    @Override
    public void restore(@Nullable Object captured) {
        RepoProcessMemos.clear();
    }
}
