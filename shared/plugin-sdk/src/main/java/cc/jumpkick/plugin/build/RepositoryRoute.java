// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import java.net.URI;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One remote repository as the engine routes it for this module: the repository's id, the URL a
 * request for it opens, and the credential that request carries — {@link In#repositories}.
 *
 * <p>The URL is where jk itself would send the request, not the repository's declared address: a
 * {@code settings.xml} mirror stands in for the repository, and Maven Central is rewritten to its
 * mirror while Central is refusing this host. A step whose own resolver fetches outside the lock
 * (a framework's build-time closure) asks these, in this order, and never Central directly.
 *
 * @param id the repository's name in {@code [repositories]} (or a built-in: {@code central},
 *     {@code google}, {@code jumpkick})
 * @param url the URL requests open, no trailing slash required
 * @param username the basic-auth user, or null when the credential is a bearer token or none
 * @param secret the basic-auth password when {@code username} is set, the bearer token when it is
 *     not, or null for an anonymous repository
 */
public record RepositoryRoute(
        String id,
        URI url,
        @Nullable String username,
        @Nullable String secret) {

    public RepositoryRoute {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(url, "url");
        if (username != null && secret == null) {
            throw new IllegalArgumentException("repository `" + id + "` names a user without a password");
        }
    }

    /** An anonymous route. */
    public static RepositoryRoute anonymous(String id, URI url) {
        return new RepositoryRoute(id, url, null, null);
    }

    /** True when requests carry no credential. */
    public boolean anonymous() {
        return secret == null;
    }

    /** True when the credential is a bearer token rather than a user and password. */
    public boolean bearer() {
        return username == null && secret != null;
    }
}
