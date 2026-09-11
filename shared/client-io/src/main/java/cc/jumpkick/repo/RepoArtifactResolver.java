// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.lock.RepoSource;
import cc.jumpkick.model.RepositorySpec;
import org.jspecify.annotations.Nullable;

/**
 * The lockfile {@code "<name>+<url>"} source-string format and named-remote vs local classification.
 * Finding the on-disk jar is {@link ArtifactLocator}.
 */
public final class RepoArtifactResolver {

    /**
     * Repo-source-name prefix for a synthetic git-materialized repo ({@code git:<coord>:<version>}).
     * This is a <em>repo source name</em> concept — distinct from {@code Dependency.GIT_PREFIX}, which
     * is a module-coordinate placeholder. Written by the git-source materializer when it registers the
     * repo, read back here to exclude such repos from the named-remote fast path.
     */
    public static final String GIT_SOURCE_PREFIX = "git:";

    /** @see RepositorySpec#JK_LOCAL */
    public static final String JK_LOCAL = RepositorySpec.JK_LOCAL;

    private RepoArtifactResolver() {}

    /**
     * The {@code <name>} before the {@code '+'} in a lockfile source ({@code "central+https://…"}),
     * or {@code null} when the source is malformed. Delegates to the shared {@link RepoSource}
     * parser (in {@code :core}), which owns the {@code <name>+<url>} split.
     */
    public static @Nullable String repoName(String source) {
        return RepoSource.parse(source).name();
    }

    /**
     * True for a <em>named remote</em> repo — one whose store under {@code repos/<name>/} holds a
     * jar fetched from that repository. {@link #JK_LOCAL} and {@code git:} sources are first-party /
     * synthesized and never live in the Maven local repository. A user remote named {@code local}
     * is a normal named remote.
     */
    public static boolean isNamedRemote(@Nullable String repoName) {
        return repoName != null
                && !repoName.isEmpty()
                && !JK_LOCAL.equals(repoName)
                && !repoName.startsWith(GIT_SOURCE_PREFIX);
    }

    /** True when {@code source} is the bare first-party store marker ({@link #JK_LOCAL}). */
    public static boolean isFirstPartySource(String source) {
        return JK_LOCAL.equals(source);
    }

    /** True when {@code repoName} is the first-party store directory ({@link #JK_LOCAL}). */
    public static boolean isFirstPartyStoreName(String repoName) {
        return JK_LOCAL.equals(repoName);
    }
}
