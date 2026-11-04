// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import cc.jumpkick.credential.RepoCredential;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A {@code [repositories.<id>]} table of {@code ~/.jk/config.toml} that carries {@code mirror-of}:
 * not a repository but this machine's route to the repositories it matches. Every request for a
 * matched repository opens {@code url} instead, and the lock goes on recording the repository
 * itself; a mirror is never in a repository group, never in a lock and has no store tree of its
 * own.
 *
 * @param id the table's name, which keys the mirror's credential
 * @param url where a matched repository's requests open
 * @param mirrorOf the repositories it stands in for
 * @param credential the table's own {@code token} or {@code username}/{@code password}, references
 *     unexpanded, or null
 * @param allowInsecure {@code allow-insecure = true}: a plaintext {@code http://} URL on a network
 *     path is accepted
 * @param file the config file that declared it, for a diagnostic
 */
public record RepositoryMirror(
        String id, URI url, MirrorOf mirrorOf, @Nullable RepoCredential credential, boolean allowInsecure, Path file) {

    public RepositoryMirror {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(mirrorOf, "mirrorOf");
        Objects.requireNonNull(file, "file");
    }

    /** True when this mirror stands in for the repository {@code repoId} at {@code repoUrl}. */
    public boolean matches(String repoId, URI repoUrl) {
        return mirrorOf.matches(repoId, repoUrl);
    }

    /** {@code mirror `corp` (~/.jk/config.toml)} — how a diagnostic names this entry. */
    public String label() {
        return "mirror `" + id + "` (" + file + ")";
    }

    /**
     * The declaration that binds this mirror's id to its URL for the credential resolver: the
     * user's own word, as a {@code [repositories]} table of the same file is.
     */
    public RepositorySpec declaration() {
        return new RepositorySpec(id, url, credential);
    }
}
