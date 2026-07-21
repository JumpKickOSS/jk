// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.credential;

/**
 * Artifact-repository credential (pure data). HTTP shapes: {@link Anonymous}, {@link Basic},
 * {@link Bearer}. Header/signing rendering lives in {@code :io}.
 */
public sealed interface RepoCredential permits RepoCredential.Anonymous, RepoCredential.Basic, RepoCredential.Bearer {

    /** No authentication — public repositories. */
    record Anonymous() implements RepoCredential {}

    /** HTTP Basic: username + password (or username + token-as-password). */
    record Basic(String username, String password) implements RepoCredential {
        public Basic {
            if (username == null) throw new IllegalArgumentException("username");
            if (password == null) password = "";
        }
    }

    /** HTTP Bearer: a single opaque token. */
    record Bearer(String token) implements RepoCredential {
        public Bearer {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("bearer token must not be blank");
            }
        }
    }

    /** Shared singleton for the no-auth case. */
    RepoCredential ANONYMOUS = new Anonymous();

    /** True when this credential carries no secret (anonymous access). */
    default boolean isAnonymous() {
        return this instanceof Anonymous;
    }
}
