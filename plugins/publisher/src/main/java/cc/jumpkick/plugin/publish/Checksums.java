// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * Hex digests for Maven checksum sidecars: {@code .md5}, {@code .sha1}, {@code .sha256}, {@code
 * .sha512} (all four for Central compatibility). The three jk would never choose are named here
 * because the sidecar file names them; the SHA-256 one is jk's own digest and goes through
 * {@link Hashing#sha256Hex(byte[])} without repeating the algorithm.
 */
public final class Checksums {

    private Checksums() {}

    public record Set(String md5, String sha1, String sha256, String sha512) {}

    public static Set of(byte[] data) {
        return new Set(
                digestHex(data, "MD5"), digestHex(data, "SHA-1"), Hashing.sha256Hex(data), digestHex(data, "SHA-512"));
    }

    /** The four digests of {@code file}, read once through a fixed buffer: never the whole file in memory. */
    public static Set of(Path file) throws IOException {
        MessageDigest md5 = Hashing.newDigest("MD5");
        MessageDigest sha1 = Hashing.newDigest("SHA-1");
        MessageDigest sha256 = Hashing.newSha256();
        MessageDigest sha512 = Hashing.newDigest("SHA-512");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[BUFFER];
            for (int n; (n = in.read(buf)) > 0; ) {
                md5.update(buf, 0, n);
                sha1.update(buf, 0, n);
                sha256.update(buf, 0, n);
                sha512.update(buf, 0, n);
            }
        }
        return new Set(
                Hashing.hex(md5.digest()),
                Hashing.hex(sha1.digest()),
                Hashing.hex(sha256.digest()),
                Hashing.hex(sha512.digest()));
    }

    /** SHA-1 of what {@code in} delivers, read through a fixed buffer; {@code in} is closed here. */
    static String sha1Hex(InputStream in) throws IOException {
        MessageDigest sha1 = Hashing.newDigest("SHA-1");
        try (in) {
            byte[] buf = new byte[BUFFER];
            for (int n; (n = in.read(buf)) > 0; ) sha1.update(buf, 0, n);
        }
        return Hashing.hex(sha1.digest());
    }

    private static final int BUFFER = 64 * 1024;

    public static String md5Hex(byte[] data) {
        return digestHex(data, "MD5");
    }

    public static String sha1Hex(byte[] data) {
        return digestHex(data, "SHA-1");
    }

    public static String sha256Hex(byte[] data) {
        return Hashing.sha256Hex(data);
    }

    public static String sha512Hex(byte[] data) {
        return digestHex(data, "SHA-512");
    }

    private static String digestHex(byte[] data, String algorithm) {
        return Hashing.hashHex(algorithm, data);
    }
}
