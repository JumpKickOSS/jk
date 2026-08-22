// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.lock.RepoSource;

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

    private RepoArtifactResolver() {}

    /**
     * The {@code <name>} before the {@code '+'} in a lockfile source ({@code "central+https://…"}),
     * or {@code null} when the source is absent/malformed. Delegates to the shared {@link RepoSource}
     * parser (in {@code :core}), which owns the {@code <name>+<url>} split.
     */
    public static String repoName(String source) {
        return RepoSource.parse(source).name();
    }

    /**
     * True for a <em>named remote</em> repo — one whose store under {@code repos/<name>/} holds a
     * jar fetched from that repository. {@code local} and {@code git:} sources are first-party /
     * synthesized and never live in the Maven local repository.
     */
    public static boolean isNamedRemote(String repoName) {
        return repoName != null
                && !repoName.isEmpty()
                && !repoName.equals("local")
                && !repoName.startsWith(GIT_SOURCE_PREFIX);
    }
}
