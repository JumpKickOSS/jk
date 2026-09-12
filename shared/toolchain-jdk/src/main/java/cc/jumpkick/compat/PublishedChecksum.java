// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * The checksum file a distribution's publisher puts beside every archive: {@code <archive>.sha512}
 * on Maven Central for Apache Maven, {@code <archive>.sha256} on services.gradle.org and on the
 * Kotlin GitHub release. A distribution that carries no pin of its own is verified against this
 * sidecar, fetched from the same origin as the archive, and refused when the sidecar is absent —
 * TLS alone is not a reason to unpack and execute a compiler.
 *
 * @param suffix appended to the archive URL to address the sidecar, dot included
 * @param algorithm the {@link java.security.MessageDigest} name the sidecar's digest is computed with
 * @param hexLength the digest width in hex digits, which a body must match to count as a digest
 */
public record PublishedChecksum(String suffix, String algorithm, int hexLength) {

    public static final PublishedChecksum SHA256 = new PublishedChecksum(".sha256", "SHA-256", 64);
    public static final PublishedChecksum SHA512 = new PublishedChecksum(".sha512", "SHA-512", 128);

    public PublishedChecksum {
        Objects.requireNonNull(suffix, "suffix");
        Objects.requireNonNull(algorithm, "algorithm");
        if (!suffix.startsWith(".") || suffix.length() < 2) {
            throw new IllegalArgumentException("sidecar suffix must start with a dot: " + suffix);
        }
        if (hexLength <= 0) throw new IllegalArgumentException("hexLength must be positive: " + hexLength);
    }

    /** The sidecar beside {@code archive}. */
    public URI beside(URI archive) {
        return URI.create(archive.toString() + suffix);
    }

    /** {@code sha512} — how the digest is named in messages. */
    public String label() {
        return suffix.substring(1).toLowerCase(Locale.ROOT);
    }
}
