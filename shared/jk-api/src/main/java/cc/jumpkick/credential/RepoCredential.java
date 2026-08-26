// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.credential;

import org.jspecify.annotations.Nullable;

/**
 * Artifact-repository credential (pure data). HTTP shapes: {@link Anonymous}, {@link Basic},
 * {@link Bearer}. Header/signing rendering lives in {@code :io}.
 *
 * <p>Printing one never prints its secret: {@link Basic#toString} and {@link Bearer#toString} mask
 * it. That is deliberately independent of the redactor, which masks free-form engine text after the
 * fact. A stack trace, a {@code System.err} in a worker, a debugger's variable pane and an
 * exception message built by string concatenation are all paths that never reach a redactor, and on
 * those paths the only thing standing between a bearer token and a build log is this type.
 */
public sealed interface RepoCredential permits RepoCredential.Anonymous, RepoCredential.Basic, RepoCredential.Bearer {

    /**
     * What a secret becomes when a credential prints itself. The same three characters
     * {@code SecretRedactor} uses, spelled again rather than shared: {@code :jk-api} reaches
     * {@code :host} and nothing else, and a mask that stops working when the redactor is
     * unreachable is not defence in depth.
     */
    String MASK = "***";

    /** No authentication — public repositories. */
    record Anonymous() implements RepoCredential {}

    /** HTTP Basic: username + password (or username + token-as-password). */
    record Basic(String username, String password) implements RepoCredential {
        public Basic {
            if (username == null) throw new IllegalArgumentException("username");
            if (password == null) password = "";
        }

        /** The username identifies; the password authenticates. Only the second one is withheld. */
        @Override
        public String toString() {
            return "Basic[username=" + username + ", password=" + MASK + "]";
        }
    }

    /** HTTP Bearer: a single opaque token. */
    record Bearer(String token) implements RepoCredential {
        public Bearer {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("bearer token must not be blank");
            }
        }

        /** A bearer credential is nothing but its secret, so there is nothing to print. */
        @Override
        public String toString() {
            return "Bearer[token=" + MASK + "]";
        }
    }

    /** Shared singleton for the no-auth case. */
    RepoCredential ANONYMOUS = new Anonymous();

    /** True when this credential carries no secret (anonymous access). */
    default boolean isAnonymous() {
        return this instanceof Anonymous;
    }

    /**
     * The half of this credential that is a secret, or null when it has none — a bearer token, or
     * Basic's password, which is where a token lands when the username is a login (the forge
     * bridge does exactly that). The username is excluded: it is a person's or a repository's name,
     * and treating it as a secret would blank ordinary build output.
     *
     * <p>One owner for the rule, because two callers need the same answer for different reasons —
     * the resolver files this value with the redactor, and the engine's publish verb files the one
     * that arrived over the socket.
     */
    default @Nullable String secret() {
        return switch (this) {
            case Bearer b -> b.token();
            case Basic b -> b.password();
            case Anonymous ignored -> null;
        };
    }
}
