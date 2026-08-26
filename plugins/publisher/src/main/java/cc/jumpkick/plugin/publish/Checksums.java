// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import cc.jumpkick.host.Hashing;

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
