// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.util.Objects;

/**
 * Text that has already been through a {@link SecretRedactor}.
 *
 * <p>The type <em>is</em> the proof. There is no public constructor and deliberately no
 * {@code Redacted.of(String)}: the only mint is {@link SecretRedactor#redactAll}, and its
 * canonical constructor is package-private so no caller outside this package can wrap raw text.
 * A sink declared in terms of {@code Redacted} therefore cannot be handed unredacted worker
 * output — the omission is a compile error rather than a review comment.
 *
 * <p>That is the whole point. Three of the four emitters of the {@code workspace-finish} event had
 * simply forgotten the redaction call, and a {@code List<String>} parameter could not tell them
 * apart from the one that remembered.
 *
 * <p><b>Scope of the promise.</b> This proves exactly what the {@link SecretRedactor} that minted
 * it was told about: the effective value of every name a {@code .env} declares, plus — on the
 * engine's event paths — the repository credentials the request resolved ({@link
 * ResolvedSecrets}). It proves nothing about a secret nobody declared, because masking here is by
 * declaration and never by name heuristic. A credential inside a URL and a credential on a process
 * command line are separate concerns with separate owners: {@code SafeUri} for the first, a secret
 * read from a file rather than passed as an argument for the second.
 */
public final class Redacted {

    private final String text;

    /** Package-private on purpose: {@link SecretRedactor} is the only mint. */
    Redacted(String text) {
        this.text = Objects.requireNonNull(text, "text");
    }

    /** The masked text — safe for the wire, an SSE stream, or the on-disk journal. */
    public String text() {
        return text;
    }

    @Override
    public String toString() {
        return text;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Redacted other && text.equals(other.text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }
}
