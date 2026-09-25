// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

/**
 * Process-wide fetch memos owned by this module: effective POMs, Gradle module metadata, repository
 * hits and version lists.
 */
public final class RepoProcessMemos {

    private RepoProcessMemos() {}

    /** Drop every memo this module owns (force, and the resolver's wider fan-out). */
    public static void clear() {
        EffectivePomBuilder.clearProcessCache();
        GradleModuleMetadata.clearParseCache();
        RepoGroup.clearProcessFetchCache();
        RepoGroup.clearProcessVersionsCache();
    }
}
