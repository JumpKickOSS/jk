// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Release authenticity: Ed25519 signature over {@code SHA256SUMS}, then hash check, before
 * materialization. Built-in key may rotate via {@code NEXT_RELEASE_KEY}; hosts can override
 * {@code [release] trusted-keys}. After pin in {@code jk.lock}, fetches verify against the pin.
 */
public final class ReleaseVerifier {

    /**
     * Baked-in JumpKick release public key (base64 X.509/SPKI Ed25519, JK-1066). The matching
     * private key is held only as the GitHub Actions secret {@code JK_RELEASE_SIGNING_KEY}
     * (PKCS#8 base64) and used by {@code scripts/sign-release.sh} on tag builds. Empty string
     * would mean "verification unavailable" — do not clear without rotating to a replacement.
     */
    public static final String BUILT_IN_KEY =
            "MCowBQYDK2VwAyEAJMjkVY8egU7YDTJGcLs/LQC8e11cwJ8cYflpdjuUcAo=";

    private final List<PublicKey> trusted;

    private ReleaseVerifier(List<PublicKey> trusted) {
        this.trusted = trusted;
    }

    /** The verifier for this host: baked-in key plus {@code [release] trusted-keys} overrides. */
    public static ReleaseVerifier current(List<String> configuredKeys) {
        List<PublicKey> keys = new ArrayList<>();
        if (!BUILT_IN_KEY.isEmpty()) parse(BUILT_IN_KEY).ifPresentOrElse(keys::add, () -> {});
        for (String k : configuredKeys) parse(k).ifPresent(keys::add);
        return new ReleaseVerifier(keys);
    }

    /** A verifier trusting exactly {@code keys} — tests, and pinned enterprise setups. */
    public static ReleaseVerifier of(List<String> keys) {
        List<PublicKey> parsed = new ArrayList<>();
        for (String k : keys) parse(k).ifPresent(parsed::add);
        return new ReleaseVerifier(parsed);
    }

    /** True when at least one trusted key is configured — verification is possible at all. */
    public boolean available() {
        return !trusted.isEmpty();
    }

    /**
     * Verify {@code signatureBase64} over {@code sumsBytes} against any trusted key. Throws with
     * an actionable message on failure — a bad signature must never degrade to a warning.
     */
    public void verify(byte[] sumsBytes, String signatureBase64) throws IOException {
        if (trusted.isEmpty()) {
            throw new IOException("no trusted release keys are configured — cannot verify this release"
                    + " (set [release] trusted-keys in config.toml, or upgrade to a client with a baked-in key)");
        }
        byte[] sig;
        try {
            sig = Base64.getDecoder().decode(signatureBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("release signature is not valid base64 — the download may be corrupt");
        }
        for (PublicKey key : trusted) {
            try {
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(key);
                verifier.update(sumsBytes);
                if (verifier.verify(sig)) return;
            } catch (GeneralSecurityException ignored) {
                // try the next trusted key
            }
        }
        throw new IOException("release signature does not verify against any trusted key —"
                + " REFUSING this release (a mirror, proxy, or the release site may be compromised)");
    }

    private static java.util.Optional<PublicKey> parse(String base64Spki) {
        try {
            byte[] der = Base64.getDecoder().decode(base64Spki.trim());
            return java.util.Optional.of(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }
}
