// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Objects;

/**
 * A git-sourced dependency. {@code canonicalUrl} is normalized; {@code originalUrl} is what the
 * user wrote. Group/name/version come from the clone at materialization; refs pin in {@code jk-lock.toml}
 * (branch tips move only via {@code jk update --git}/{@code jk fetch}). {@code shallow} is true for
 * explicit tag tables; URL-embedded refs always full-clone.
 */
public record GitSource(
        String canonicalUrl,
        String originalUrl,
        GitRefSpec ref,
        String path,
        boolean submodules,
        boolean verifySignature,
        boolean shallow) {

    public GitSource {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl");
        Objects.requireNonNull(originalUrl, "originalUrl");
        Objects.requireNonNull(ref, "ref");
        // path nullable
    }

    /** {@code shallow} defaults true for {@link GitRefSpec.Tag}; use the full ctor for URL-embedded tags. */
    public GitSource(
            String canonicalUrl,
            String originalUrl,
            GitRefSpec ref,
            String path,
            boolean submodules,
            boolean verifySignature) {
        this(canonicalUrl, originalUrl, ref, path, submodules, verifySignature, ref instanceof GitRefSpec.Tag);
    }

    /** Default options: submodules=true, verifySignature=false, no path. */
    public static GitSource of(String canonicalUrl, String originalUrl, GitRefSpec ref) {
        return new GitSource(canonicalUrl, originalUrl, ref, null, true, false);
    }

    public GitSource withPath(String path) {
        return new GitSource(canonicalUrl, originalUrl, ref, path, submodules, verifySignature, shallow);
    }

    public GitSource withSubmodules(boolean submodules) {
        return new GitSource(canonicalUrl, originalUrl, ref, path, submodules, verifySignature, shallow);
    }

    public GitSource withVerifySignature(boolean verifySignature) {
        return new GitSource(canonicalUrl, originalUrl, ref, path, submodules, verifySignature, shallow);
    }
}
