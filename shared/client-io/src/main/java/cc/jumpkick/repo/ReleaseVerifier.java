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
import java.util.Arrays;
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
            "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEA2t27ZGJXSS9btuDOBzZN04fq8qcp9Ej/tNbmCpCo10Y6wjcQQY8sJfva4zqvhdZNZz/OEYvwePuZIkTVwmrCOU5jKHeHpP/9m9gYTx7DKr7o5koU/26UOi8kII2LfgU7J5iaYkVR3jUX54lGfXJbNJo2VY7aFRPojW+aCBAI7O0GbQ2h60HK12ltdIk3yomWbBpEYs7XTCScU+jAz2RKpznX966Ue9Obhw5r1/hDITBLbCSEChjsVXwx1343k17xXVgreO8X+gwrwBDw9MwSIpGg5LwJ7enLpW6ua8sfLRnjhyyeBJ+RMrc30KVHfPsgYuSvlF1iCcokM8JJBOK7XGKQSszUn2D/fPC5xjOmDT/4K6c0GnSmybSfzL6zUa5ShIkuiq4eix+WfJ7PGl77vqxQUT4/nURMsFVQJ+Qe9+7pegXbW9/oLZ01/+6AKuZaf7qyMN89p3nGJiOTgACOygY8YEc/Xl3Ue4zxgbu/dR+S3P8dATD2iBhGs6AHR8OPAgMBAAE=";

    /** Unsigned RSA modulus bytes used by the PowerShell bootstrap verifier. */
    public static final String BUILT_IN_RSA_MODULUS =
            "2t27ZGJXSS9btuDOBzZN04fq8qcp9Ej/tNbmCpCo10Y6wjcQQY8sJfva4zqvhdZNZz/OEYvwePuZIkTVwmrCOU5jKHeHpP/9m9gYTx7DKr7o5koU/26UOi8kII2LfgU7J5iaYkVR3jUX54lGfXJbNJo2VY7aFRPojW+aCBAI7O0GbQ2h60HK12ltdIk3yomWbBpEYs7XTCScU+jAz2RKpznX966Ue9Obhw5r1/hDITBLbCSEChjsVXwx1343k17xXVgreO8X+gwrwBDw9MwSIpGg5LwJ7enLpW6ua8sfLRnjhyyeBJ+RMrc30KVHfPsgYuSvlF1iCcokM8JJBOK7XGKQSszUn2D/fPC5xjOmDT/4K6c0GnSmybSfzL6zUa5ShIkuiq4eix+WfJ7PGl77vqxQUT4/nURMsFVQJ+Qe9+7pegXbW9/oLZ01/+6AKuZaf7qyMN89p3nGJiOTgACOygY8YEc/Xl3Ue4zxgbu/dR+S3P8dATD2iBhGs6AHR8OP";

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
     * The latest-release pointer: the one mutable object under {@code releases/}, so it is signed
     * data rather than a bare version string. {@code version} is the release directory the pointer
     * names; {@code issued} is when the pointer was written, in Unix seconds.
     */
    public record Pointer(String version, long issued) {}

    /**
     * One {@code LATEST} object. {@code signedBytes} are the exact {@code version} and {@code
     * issued} lines the signature covers; {@code signature} is the base64 on the third line.
     */
    public record SignedPointer(Pointer pointer, byte[] signedBytes, String signature) {}

    private static final Pattern POINTER_OBJECT =
            Pattern.compile("version ([0-9]+\\.[0-9]+\\.[0-9]+(?:[-.][A-Za-z0-9]+)*)\\n"
                    + "issued ([0-9]{1,18})\\n"
                    + "signature ([A-Za-z0-9+/]+={0,2})\\n");

    /**
     * Parse one {@code LATEST} object: {@code version <v>}, {@code issued <unix-seconds>} and
     * {@code signature <base64>}, each LF-terminated, nothing else. The signature covers the exact
     * bytes of the first two lines, so the verifier's reading has to be as literal as the signer's
     * writing — a CRLF, a missing signature line or a version that is not a plain version token is
     * refused, not tolerated.
     */
    public static SignedPointer parseSignedPointer(byte[] objectBytes) throws IOException {
        String text;
        try {
            text = StandardCharsets.US_ASCII
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(objectBytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IOException("latest-release pointer is not ASCII", e);
        }
        var match = POINTER_OBJECT.matcher(text);
        if (!match.matches()) {
            throw new IOException("latest-release pointer is malformed — expected exactly"
                    + " 'version <x.y.z>', 'issued <unix-seconds>' and 'signature <base64>',"
                    + " LF-terminated");
        }
        int signatureLine = text.lastIndexOf("\nsignature ");
        return new SignedPointer(
                new Pointer(match.group(1), Long.parseLong(match.group(2))),
                Arrays.copyOf(objectBytes, signatureLine + 1),
                match.group(3));
    }

    /** The version and issued time {@link #parseSignedPointer} reads out of the object. */
    public static Pointer parsePointer(byte[] objectBytes) throws IOException {
        return parseSignedPointer(objectBytes).pointer();
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
