// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolve;

import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.GradleModuleMetadata;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.KmpRedirects;

/**
 * Fan-out over every process-wide resolve memo. {@code --force} and force-revalidate {@link
 * #clearAll clear} them all so POMs, GMM, KMP selections, local fetch hits, version lists and the
 * remembered misses cannot stale-hit; the idle engine {@link #dropMemos drops} the positive ones
 * and keeps the misses, which are bounded by their own time-to-live and answer a re-lock's 404s.
 */
public final class ResolveProcessCacheControl {

    private ResolveProcessCacheControl() {}

    /** How many entries each positive memo held when the idle engine dropped it. */
    public record Dropped(int effectivePoms, int repositoryHits, int versionLists, int moduleMetadata) {}

    /** Drop the positive resolve memos and say how many entries went; for the idle engine. */
    public static Dropped dropMemos() {
        int poms = EffectivePomBuilder.dropProcessMemo();
        int hits = RepoGroup.dropHitMemos();
        int versions = RepoGroup.dropVersionsMemo();
        int modules = GradleModuleMetadata.dropParseMemo() + KmpRedirects.dropProcessMemo();
        return new Dropped(poms, hits, versions, modules);
    }

    /** Drop all process resolve memos (force / tests). */
    public static void clearAll() {
        EffectivePomBuilder.clearProcessCache();
        GradleModuleMetadata.clearParseCache();
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        KmpRedirects.clearProcessCache();
    }
}
