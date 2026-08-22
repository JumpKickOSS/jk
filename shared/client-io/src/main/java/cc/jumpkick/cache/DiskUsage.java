// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * File-tree size accounting that does not double-count hard-linked files.
 *
 * <p>When two trees share an inode, a naïve {@code Files.size} sum reports ~2× true disk use.
 * This helper deduplicates on {@code (dev, ino)} so each underlying blob contributes once — same
 * idea as {@code du} across hard links.
 *
 * <p>Only files with {@code nlink > 1} enter the seen-set at all (a single-link file cannot be
 * met twice), and the set itself is a primitive open-addressed long set — the previous
 * one-boxed-{@code fileKey}-per-file {@code HashSet} allocated tens of MB per cache-vitals walk
 * on a large store, every 60&nbsp;s while a dashboard tab was open. Platforms without
 * the {@code unix:} attribute view (Windows) fall back to the old per-{@code fileKey} object set.
 */
public final class DiskUsage {

    private DiskUsage() {}

    /** File count (directory entries) and unique-byte size for a tree or exclusive multi-tree walk. */
    public record Stats(long files, long bytes) {}

    /**
     * Walk one directory tree. Every regular file is counted in {@link Stats#files()}; each
     * distinct inode contributes its size once (hard links within the tree are not
     * double-counted).
     */
    public static Stats of(Path dir) throws IOException {
        return exclusive(List.of(dir))[0];
    }

    /**
     * Walk several trees <em>in order</em>. File counts include every directory entry. Byte size
     * for a hard-linked group is attributed to the <strong>first</strong> tree that contains a
     * link — later trees add 0 bytes for that key. Put store {@code sha256/} before {@code repos/}
     * so leftover shared inodes are not counted twice.
     *
     * <p>Missing or unreadable roots contribute zeros. A null {@code fileKey} on the fallback
     * path (rare providers) falls back to the absolute path so accounting never drops a file.
     */
    public static Stats[] exclusive(List<Path> roots) throws IOException {
        Objects.requireNonNull(roots, "roots");
        SeenLinks seen = new SeenLinks();
        Stats[] out = new Stats[roots.size()];
        for (int i = 0; i < roots.size(); i++) {
            out[i] = walkExclusive(roots.get(i), seen);
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

    private static Stats walkExclusive(Path dir, SeenLinks seen) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return new Stats(0, 0);
        }
        long files = 0;
        long bytes = 0;
        try (var stream = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (seen.unixSupported) {
                    Map<String, Object> u = null;
                    try {
                        u = Files.readAttributes(p, "unix:isRegularFile,nlink,size,dev,ino");
                    } catch (UnsupportedOperationException | IllegalArgumentException e) {
                        seen.unixSupported = false; // fall through to the fileKey path for this file
                    } catch (IOException unreadable) {
                        continue;
                    }
                    if (u != null) {
                        if (!Boolean.TRUE.equals(u.get("isRegularFile"))) continue;
                        files++;
                        long nlink = ((Number) u.get("nlink")).longValue();
                        // A single-link file cannot be met again on any tree — skip the set entirely.
                        if (nlink <= 1
                                || seen.addInode(
                                        ((Number) u.get("dev")).longValue(), ((Number) u.get("ino")).longValue())) {
                            bytes += ((Number) u.get("size")).longValue();
                        }
                        continue;
                    }
                }
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
                if (seen.addObject(key)) {
                    bytes += attrs.size();
                }
            }
        }
        return new Stats(files, bytes);
    }

    /**
     * Cross-root seen-set: a primitive open-addressed {@code (dev, ino)} long set on unix,
     * an object set of {@code fileKey}s elsewhere. ~8 bytes per multi-linked file instead of a
     * boxed key + node per file.
     */
    private static final class SeenLinks {
        boolean unixSupported = true;
        private Set<Object> objects; // fallback platforms only, lazily created
        private long[] slots = new long[1 << 10];
        private int used;
        private boolean hasZero;

        boolean addInode(long dev, long ino) {
            long key = dev * 0x9E3779B97F4A7C15L + ino;
            if (key == 0) {
                boolean fresh = !hasZero;
                hasZero = true;
                return fresh;
            }
            if (used >= slots.length / 2) grow();
            int mask = slots.length - 1;
            int i = (int) (mix(key) & mask);
            while (true) {
                long cur = slots[i];
                if (cur == 0) {
                    slots[i] = key;
                    used++;
                    return true;
                }
                if (cur == key) return false;
                i = (i + 1) & mask;
            }
        }

        boolean addObject(Object key) {
            if (objects == null) objects = new HashSet<>();
            return objects.add(key);
        }

        private void grow() {
            long[] old = slots;
            slots = new long[old.length << 1];
            used = 0;
            boolean zero = hasZero;
            hasZero = false;
            for (long k : old) {
                if (k != 0) addInode(0, k); // dev already folded into k; re-insert raw
            }
            hasZero = zero;
        }

        private static long mix(long z) {
            z = (z ^ (z >>> 33)) * 0xFF51AFD7ED558CCDL;
            return z ^ (z >>> 33);
        }
    }
}
