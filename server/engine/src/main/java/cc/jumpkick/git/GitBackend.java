// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.git;

import cc.jumpkick.model.GitSource;
import cc.jumpkick.plugin.Extension;
import cc.jumpkick.plugin.build.Phase;
import java.io.IOException;
import java.util.Set;

/**
 * In-process git SPI: {@link GitCliExtension} preferred, {@link JGitExtension} fallback.
 * {@link GitFetcher} selects the backend ({@code JK_GIT_BACKEND}).
 */
public interface GitBackend extends Extension {

    /** Git backends participate in the {@link Phase#RESOLVE} phase (fetching git-sourced deps). */
    @Override
    default Set<Phase> phases() {
        return Set.of(Phase.RESOLVE);
    }

    /** Enumerate the remote's tags + {@code HEAD} sha via {@code ls-remote} — no clone. */
    GitFetcher.RemoteRefs listRefs(GitSource source) throws IOException;

    /** Resolve the ref to a SHA and materialize a checkout, using (or bypassing) the cache. */
    GitFetcher.Fetched fetch(GitSource source, boolean noCache) throws IOException;

    /** Re-resolve the ref and fail with {@link GitFetcher.TagRewriteException} if the SHA changed. */
    void verifyLocked(GitSource source, String expectedSha) throws IOException;

    /** Resolve the ref to a SHA plus commit time and nearest tag, for git-source versioning. */
    GitFetcher.RefInfo resolveRef(GitSource source) throws IOException;
}
