// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.format;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * CAS-sharded per-file format stamp store under {@code <cache>/format-stamps/}. A hit means the
 * file is already clean for the config in the key. Fail-open on I/O errors.
 *
 * <p>Hits are existence-only (no mtime touch). Age-TTL GC still evicts cold entries; a lost stamp
 * costs one extra format pass.
 */
final class FormatStampCache {

    private final Path root;

    FormatStampCache(Path root) {
        this.root = root;
    }

    /**
     * True when a valid stamp exists for {@code key}; false on any I/O error. Hits do not refresh
     * mtime — a 1 600-file clean run used to pay 1 600 extra writes for LRU, which dominated the
     * skip path. Age-TTL GC still evicts unused stamps; a lost stamp costs one extra format.
     */
    boolean contains(String key) {
        try {
            return Files.exists(stampPath(key));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Record a stamp for {@code key}. Creates parent dirs as needed. Silently ignores I/O errors —
     * advisory cache, never critical. Existing stamps get their mtime refreshed (reuse = hot).
     */
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
