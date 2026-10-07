// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

/**
 * Process-wide fetch memos owned by this module: effective POMs, parsed store POMs and sidecars,
 * Gradle module metadata, repository hits, version lists and metadata bodies.
 */
public final class RepoProcessMemos {

    private RepoProcessMemos() {}

    /** Drop every memo this module owns (force, and the resolver's wider fan-out). */
    public static void clear() {
        EffectivePomBuilder.clearProcessCache();
        GradleModuleMetadata.clearParseCache();
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
        MavenMetadataCache.dropBodyMemo();
    }

    /** Drop the store-file memos (parsed POMs, sidecars, metadata bodies); how many entries went. For the idle engine. */
    public static int dropStoreFileMemos() {
        return StorePoms.dropMemo() + RepoArtifactStore.dropSidecarMemo() + MavenMetadataCache.dropBodyMemo();
    }
}
