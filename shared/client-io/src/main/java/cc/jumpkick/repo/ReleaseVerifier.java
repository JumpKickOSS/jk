// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Verifies RSA/SHA-256 signatures over the exact {@code SHA256SUMS} bytes before artifact hashes
 * are trusted. The built-in key and optional {@code [release] trusted-keys} use base64 SPKI.
 */
public final class ReleaseVerifier {

    /**
     * Baked-in JumpKick RSA-3072 release public key as base64 X.509/SPKI. The matching private key
     * is held outside the repository and supplied to release automation as
     * {@code JK_RELEASE_RSA_SIGNING_KEY}.
     */
    public static final String BUILT_IN_KEY =
            "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEA62bXAMmyIpPgiFzT9lcuIPWvvXHmWfGDPbMJAG1lRlbSJ9EFRahqkie0LQaFtXn8W3l2BP/9D0DwdXztS/eVo8WqSNMOZo/srBKrViVJGEOFm0fDmhqrlA3bCZz43+DgFjj7SacI2nJVB4PRjV5jvRwBnZrIUwcvynIQmx2SoWoKgudoje7vNM7UkYmEnZExfmiPQaPmSYCKzXA4pP5KPWD+49bo7o3cLeiO5/Shc27OC0IvK+Vj8CUe4URSt5zHjHUpiE+h4SVTMrGoJg9rgWmRgMHdshsq3aoAkA3jC/YB5SzLwUJeObWGP8I9w7yj8uiSTNIt3KslbRfVtb4vbNoZ4zKPMkCaYhy5ar0sGOqxW97wobIWBiX5pT+knluZErrsJFWpx2dQtRtb2wPovihL7Z9Q18vZb371Gx+rzkNi7jdFvWaGYgsraf01l63Gg2bfy1bleSLhDKmh94yGMoHfszEcE1785xteYOdVSwawUPwWgx8iZ7a4lqOL0MrrAgMBAAE=";

    /** Unsigned RSA modulus bytes used by the PowerShell bootstrap verifier. */
    public static final String BUILT_IN_RSA_MODULUS =
            "62bXAMmyIpPgiFzT9lcuIPWvvXHmWfGDPbMJAG1lRlbSJ9EFRahqkie0LQaFtXn8W3l2BP/9D0DwdXztS/eVo8WqSNMOZo/srBKrViVJGEOFm0fDmhqrlA3bCZz43+DgFjj7SacI2nJVB4PRjV5jvRwBnZrIUwcvynIQmx2SoWoKgudoje7vNM7UkYmEnZExfmiPQaPmSYCKzXA4pP5KPWD+49bo7o3cLeiO5/Shc27OC0IvK+Vj8CUe4URSt5zHjHUpiE+h4SVTMrGoJg9rgWmRgMHdshsq3aoAkA3jC/YB5SzLwUJeObWGP8I9w7yj8uiSTNIt3KslbRfVtb4vbNoZ4zKPMkCaYhy5ar0sGOqxW97wobIWBiX5pT+knluZErrsJFWpx2dQtRtb2wPovihL7Z9Q18vZb371Gx+rzkNi7jdFvWaGYgsraf01l63Gg2bfy1bleSLhDKmh94yGMoHfszEcE1785xteYOdVSwawUPwWgx8iZ7a4lqOL0Mrr";

    /** Unsigned RSA public exponent bytes used by the PowerShell bootstrap verifier. */
    public static final String BUILT_IN_RSA_EXPONENT = "AQAB";

    private final List<PublicKey> trusted;

    private ReleaseVerifier(List<PublicKey> trusted) {
        this.trusted = trusted;
    }

    /** The verifier for this host: baked-in key plus {@code [release] trusted-keys} overrides. */
    public static ReleaseVerifier current(List<String> configuredKeys) {
        return of(
                Stream.concat(Stream.of(BUILT_IN_KEY), configuredKeys.stream()).toList());
    }

    /** A verifier trusting exactly {@code keys} — tests, and pinned enterprise setups. */
    public static ReleaseVerifier of(List<String> keys) {
        return new ReleaseVerifier(keys.stream()
                .map(ReleaseVerifier::parse)
                .flatMap(Optional::stream)
                .toList());
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
                Signature verifier = Signature.getInstance("SHA256withRSA");
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

    private static Optional<PublicKey> parse(String base64Spki) {
        try {
            byte[] der = Base64.getDecoder().decode(base64Spki.trim());
            return Optional.of(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Return the unique digest for {@code artifactName} from a strict coreutils checksum manifest.
     * Every non-final line must be {@code <64 hex><two spaces><plain filename>}.
     */
    public static String sha256For(byte[] sumsBytes, String artifactName) throws IOException {
        return find(sumsBytes, artifactName)
                .orElseThrow(() -> new IOException("release SHA256SUMS has no unique exact entry for " + artifactName));
    }

    /**
     * The digest for {@code artifactName}, or empty when the manifest simply has no such entry. A
     * manifest that is malformed, not UTF-8, or names an artifact twice still throws: absence is an
     * answer, corruption is not. Strict LF line endings — the signer and every verifier agree on
     * the exact bytes, so a CRLF manifest is a different file, not the same file typed on Windows.
     */
    public static Optional<String> find(byte[] sumsBytes, String artifactName) throws IOException {
        String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(sumsBytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IOException("release SHA256SUMS is not valid UTF-8", e);
        }

        Pattern entry = Pattern.compile("^([0-9A-Fa-f]{64})  ([A-Za-z0-9][A-Za-z0-9._-]*)$");
        var seen = new HashSet<String>();
        String found = null;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty() && i == lines.length - 1) continue;
            var match = entry.matcher(line);
            if (!match.matches()) {
                throw new IOException("release SHA256SUMS has a malformed entry");
            }
            String name = match.group(2);
            if (!seen.add(name)) {
                throw new IOException("release SHA256SUMS has a duplicate entry for " + name);
            }
            if (name.equals(artifactName)) found = match.group(1).toLowerCase(Locale.ROOT);
        }
        return Optional.ofNullable(found);
    }
}
