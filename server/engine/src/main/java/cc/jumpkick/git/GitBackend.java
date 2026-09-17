// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.git;

import cc.jumpkick.model.GitSource;
import cc.jumpkick.plugin.Extension;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * In-process git SPI: {@link GitCliExtension} preferred, {@link JGitExtension} fallback.
 * {@link GitFetcher} selects the backend ({@code JK_GIT_BACKEND}).
 */
public interface GitBackend extends Extension {

    /** Enumerate the remote's tags + {@code HEAD} sha via {@code ls-remote} — no clone. */
    GitFetcher.RemoteRefs listRefs(GitSource source) throws IOException;

    /** Resolve the ref to a SHA and materialize a checkout, using (or bypassing) the cache. */
    GitFetcher.Fetched fetch(GitSource source, boolean noCache) throws IOException;

    /** Re-resolve the ref and fail with {@link GitFetcher.TagRewriteException} if the SHA changed. */
    void verifyLocked(GitSource source, String expectedSha) throws IOException;

    /** Resolve the ref to a SHA plus commit time and nearest tag, for git-source versioning. */
    GitFetcher.RefInfo resolveRef(GitSource source) throws IOException;

    /**
     * Describe the checkout containing {@code dir}: its {@code HEAD}, branch, commit time, dirty
     * state and nearest tag. Empty when {@code dir} is outside every repository or {@code HEAD}
     * names no commit yet.
     */
    Optional<GitFetcher.Worktree> describeWorktree(Path dir) throws IOException;
}
