// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * File-tree size accounting that does not double-count hard-linked files.
 *
 * <p>CAS blobs under {@code sha256/…} and Maven-layout views under {@code repos/…} share one
 * allocation via hard link. Naïve {@code Files.size} sums over both trees report ~2× true disk
 * use. This helper keys on {@link BasicFileAttributes#fileKey()} (inode / NTFS file id) so each
 * underlying blob contributes once — same idea as {@code du} across hard links.
 */
public final class DiskUsage {

    private DiskUsage() {}

    /** File count (directory entries) and unique-byte size for a tree or exclusive multi-tree walk. */
    public record Stats(long files, long bytes) {}

    /**
     * Walk one directory tree. Every regular file is counted in {@link Stats#files()}; each
     * distinct {@code fileKey} contributes its size once (hard links within the tree are not
     * double-counted).
     */
    public static Stats of(Path dir) throws IOException {
        return exclusive(List.of(dir))[0];
    }

    /**
     * Walk several trees <em>in order</em>. File counts include every directory entry. Byte size
     * for a hard-linked group is attributed to the <strong>first</strong> tree that contains a
     * link — later trees add 0 bytes for that key. Put the CAS ({@code sha256/}) before
     * {@code repos/} so blob bytes land under CAS and repo hard links do not inflate storage.
     *
     * <p>Missing or unreadable roots contribute zeros. A null {@code fileKey} (rare providers)
     * falls back to the absolute path so accounting never drops a file.
     */
    public static Stats[] exclusive(List<Path> roots) throws IOException {
        Objects.requireNonNull(roots, "roots");
        Set<Object> seenKeys = new HashSet<>();
        Stats[] out = new Stats[roots.size()];
        for (int i = 0; i < roots.size(); i++) {
            out[i] = walkExclusive(roots.get(i), seenKeys);
        }
        return out;
    }

    /** Convenience: {@link #exclusive(List)} over a vararg. */
    public static Stats[] exclusive(Path... roots) throws IOException {
        return exclusive(List.of(roots));
    }

    /** Sum of {@link Stats#bytes()} — true unique storage across exclusive sections. */
    public static long totalBytes(Stats... parts) {
        long n = 0;
        for (Stats s : parts) {
            if (s != null) n += s.bytes();
        }
        return n;
    }

    /** Sum of {@link Stats#files()}. */
    public static long totalFiles(Stats... parts) {
        long n = 0;
        for (Stats s : parts) {
            if (s != null) n += s.files();
        }
        return n;
    }

    private static Stats walkExclusive(Path dir, Set<Object> seenKeys) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return new Stats(0, 0);
        }
        long files = 0;
        long bytes = 0;
        try (var stream = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(p, BasicFileAttributes.class);
                } catch (IOException unreadable) {
                    continue;
                }
                if (!attrs.isRegularFile()) continue;
                files++;
                Object key = attrs.fileKey();
                if (key == null) {
                    key = p.toAbsolutePath().normalize();
                }
                if (seenKeys.add(key)) {
                    bytes += attrs.size();
                }
            }
        }
        return new Stats(files, bytes);
    }
}
