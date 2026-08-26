// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * The one digest surface in the tree: SHA-256 by default, any JDK-mandated algorithm on
 * request, and the single spelling of lowercase hex. Lives in the {@code :host} leaf so the
 * native client, the engine and every plugin worker share it — naming the algorithm or
 * hand-encoding hex anywhere else is a defect, not a style choice.
 *
 * <p>Two kinds of caller meet here and they are not the same concern. jk's own content hashing
 * never names an algorithm: it asks for {@link #sha256Hex} or {@link #newSha256}. A digest whose
 * algorithm is dictated by a foreign format — a Maven {@code .sha1} sidecar, Central's four
 * required checksums, Google's Android SDK feed — names it at the call site, next to the
 * {@code .sha1} it pairs with, and reaches it through {@link #newDigest} / {@link #fileHex} /
 * {@link #hashHex}. That is why those overloads exist; it is not an invitation to spell
 * {@code "SHA-256"} again.
 */
public final class Hashing {

    /** Buffer for every streamed file digest — one size, so two callers cannot disagree about it. */
    private static final int DIGEST_BUFFER = 64 * 1024;

    private Hashing() {}

    public static String sha256Hex(byte[] data) {
        return hex(sha256(data));
    }

    public static String sha256Hex(String data) {
        return sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * SHA-256 of a file's contents, computed by streaming through a fixed buffer so a
     * multi-hundred-MB artifact is never held in memory at once.
     */
    public static String sha256Hex(Path file) throws IOException {
        return fileHex(newSha256(), file);
    }

    /**
     * Hex digest of a file under {@code algorithm}, streamed through a fixed buffer so a large artifact
     * is never held in memory. The multi-algorithm sibling of {@link #sha256Hex(Path)} — needed because
     * Maven publishes {@code .sha1} sidecars, so validating a candidate against what a repository
     * advertises means hashing with SHA-1 rather than jk's own SHA-256.
     */
    public static String fileHex(String algorithm, Path file) throws IOException {
        return fileHex(newDigest(algorithm), file);
    }

    private static String fileHex(MessageDigest md, Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[DIGEST_BUFFER];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return hex(md.digest());
    }

    /** A fresh SHA-256 {@link MessageDigest} for incremental hashing. */
    public static MessageDigest newSha256() {
        return newDigest("SHA-256");
    }

    public static byte[] sha256(byte[] data) {
        return newSha256().digest(data);
    }

    /**
     * A fresh {@link MessageDigest} for {@code algorithm} (e.g. {@code "SHA-1"}, {@code "MD5"}) — the
     * foreign-format case only; jk's own hashing calls {@link #newSha256}. The JDK mandates these
     * algorithms on every JVM, so absence is a JVM defect, not a recoverable error.
     */
    public static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " not available on this JVM", e);
        }
    }

    /** Lowercase hex of raw digest bytes — the one spelling of {@code HexFormat.of().formatHex(..)}. */
    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** Hex digest of {@code data} under {@code algorithm} (one-shot). */
    public static String hashHex(String algorithm, byte[] data) {
        return hex(newDigest(algorithm).digest(data));
    }

    /**
     * True when {@code s} is a non-empty run of hex digits, either case. Case-insensitive because
     * every producer jk reads from is somebody else's: a {@code .sha1} sidecar, a repository feed,
     * a path a previous jk wrote. jk's own hex is always lowercase ({@link #hex}), so a validator
     * that rejected uppercase would only ever reject other people's spelling of the same digest.
     */
    public static boolean isHex(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }

    /** True when {@code s} is exactly {@code length} hex digits — a digest of a known width. */
    public static boolean isHex(String s, int length) {
        return s != null && s.length() == length && isHex(s);
    }

    /**
     * The digest carried by a checksum sidecar body, lowercased, or empty when the body is not one.
     *
     * <p>A {@code .sha1} / {@code .sha256} sidecar is either a bare digest or the {@code sha1sum}
     * form, {@code <hex>  <filename>}; take the first token either way. {@code hexLength} is the
     * width the algorithm mandates (40 for SHA-1, 64 for SHA-256) and is checked, because a
     * repository that serves an HTML error page with HTTP 200 — or a test server that
     * prefix-matches the artifact path — answers a sidecar request with something that is not a
     * digest. Anything that is not {@code hexLength} hex digits is treated as no sidecar at all.
     */
    public static Optional<String> checksumFromSidecar(String body, int hexLength) {
        if (body == null) return Optional.empty();
        String first = body.strip().split("\\s+", 2)[0];
        return isHex(first, hexLength) ? Optional.of(first.toLowerCase(Locale.ROOT)) : Optional.empty();
    }
}
