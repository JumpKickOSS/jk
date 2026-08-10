// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolve;

import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.GradleModuleMetadata;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.KmpRedirects;

/**
 * Fan-out clear for every process-wide resolve memo. {@code --force} and force-revalidate must
 * call this so POMs, GMM, KMP selections, local fetch hits, and version lists cannot stale-hit.
 */
public final class ResolveProcessCacheControl {

    private ResolveProcessCacheControl() {}

    /** Drop all process resolve memos (force / tests). */
    public static void clearAll() {
        EffectivePomBuilder.clearProcessCache();
        GradleModuleMetadata.clearParseCache();
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        KmpRedirects.clearProcessCache();
    }
}
