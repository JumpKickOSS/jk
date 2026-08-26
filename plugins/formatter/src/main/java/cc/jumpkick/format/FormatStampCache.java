// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;

/**
 * CAS-sharded per-file format stamp store under {@code <cache>/format-stamps/}. A hit means the
 * file is settled for the config in the key. Fail-open on I/O errors.
 *
 * <p>Hits are existence-only (no mtime touch). Age-TTL GC still evicts cold entries; a lost stamp
 * costs one extra format pass.
 */
final class FormatStampCache {

    private final Path root;
    private final String configKey;

    /**
     * {@code configKey} is the host's {@code FormatKey} digest, verbatim. The worker never derives
     * its own.
     */
    FormatStampCache(Path root, String configKey) {
        this.root = root;
        this.configKey = configKey;
    }

    /**
     * The stamp key for a file whose raw bytes are {@code fileBytes}: SHA-256 over the run's config
     * digest and the content. Null for absent bytes (fail-open cache miss).
     */
    String keyFor(byte[] fileBytes) {
        if (fileBytes == null) return null;
        MessageDigest md = Hashing.newSha256();
        md.update((configKey + "\n").getBytes(StandardCharsets.UTF_8));
        md.update(fileBytes);
        return Hashing.hex(md.digest());
    }

    boolean contains(String key) {
        try {
            return Files.exists(stampPath(key));
        } catch (Exception e) {
            return false;
        }
    }

    void record(String key) {
        try {
            Path p = stampPath(key);
            Files.createDirectories(p.getParent());
            if (!Files.exists(p)) {
                Files.writeString(p, "");
            } else {
                Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis()));
            }
        } catch (IOException ignored) {
        }
    }

    private Path stampPath(String hex64) {
        return root.resolve(hex64.substring(0, 2))
                .resolve(hex64.substring(2, 4))
                .resolve(hex64.substring(4));
    }
}
