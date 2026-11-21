// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import cc.jumpkick.host.Hashing;
import dev.sigstore.KeylessSigner;
import dev.sigstore.KeylessSignerException;
import dev.sigstore.bundle.Bundle;
import dev.sigstore.trustroot.SigstoreConfigurationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;

/**
 * Production {@link SigstoreSigner}: keyless OIDC signing against the public Sigstore instance
 * (Fulcio + Rekor). On CI runners with an OIDC token issuer in scope (GitHub Actions, GitLab,
 * etc.), sigstore-java auto-detects credentials; locally, it falls back to a browser-based
 * device-code flow.
 *
 * <p>Wraps {@link KeylessSigner} so that {@code dev.sigstore.*} types never leak across the {@code
 * :supply-chain} module boundary — {@link KeylessSignerException} surfaces as {@link IOException},
 * which is what every {@code :cli} call site already handles.
 */
public final class KeylessSigstoreSigner implements SigstoreSigner, AutoCloseable {

    /** Signs a SHA-256 digest; {@link KeylessSigner#sign(byte[])} in production. */
    @FunctionalInterface
    interface DigestSigner {
        Bundle sign(byte[] sha256) throws KeylessSignerException;
    }

    private final DigestSigner signer;
    private final Runnable onClose;

    KeylessSigstoreSigner(DigestSigner signer, Runnable onClose) {
        this.signer = signer;
        this.onClose = onClose;
    }

    /** Initialise a signer using the public Sigstore defaults. */
    public static KeylessSigstoreSigner sigstorePublic() throws IOException {
        try {
            KeylessSigner signer =
                    KeylessSigner.builder().sigstorePublicDefaults().build();
            return new KeylessSigstoreSigner(signer::sign, signer::close);
        } catch (GeneralSecurityException | SigstoreConfigurationException | RuntimeException e) {
            throw new IOException("failed to initialise Sigstore keyless signer: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] signBundle(Path artifact) throws IOException {
        byte[] digest = Hashing.unhex(Hashing.sha256Hex(artifact));
        try {
            return signer.sign(digest).toJson().getBytes(StandardCharsets.UTF_8);
        } catch (KeylessSignerException e) {
            throw new IOException("Sigstore signing failed: " + e.getMessage(), e);
        }
    }

    /**
     * A DSSE envelope whose one signature is keyless, and the Sigstore bundle of that signature:
     * the certificate of its short-lived key and its transparency-log entry.
     */
    public record Attestation(byte[] envelope, byte[] bundle) {}

    /**
     * Attest {@code statement}: sign its DSSE pre-authentication encoding as the file {@link
     * #signBundle} would sign, so the bundle verifies against that encoding and its signature is
     * the envelope's.
     */
    public Attestation attest(byte[] statement) throws IOException {
        Bundle bundle;
        try {
            bundle = signer.sign(Hashing.sha256(Dsse.pae(Dsse.IN_TOTO, statement)));
        } catch (KeylessSignerException e) {
            throw new IOException("Sigstore signing failed: " + e.getMessage(), e);
        }
        byte[] signature = bundle.getMessageSignature()
                .orElseThrow(() -> new IOException("Sigstore signed the statement without a message signature"))
                .getSignature();
        return new Attestation(
                Dsse.envelope(statement, null, signature), bundle.toJson().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        onClose.run();
    }
}
