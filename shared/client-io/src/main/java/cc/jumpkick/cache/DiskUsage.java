// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

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
     * <p>Missing or unreadable roots contribute zeros. When {@code fileKey} is null (common on
     * Windows), hard links are deduplicated through {@link SameFileKeys}.
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
                        long nlink = attr(u, "nlink");
                        // A single-link file cannot be met again on any tree — skip the set entirely.
                        if (nlink <= 1 || seen.addInode(attr(u, "dev"), attr(u, "ino"))) {
                            bytes += attr(u, "size");
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
                // Windows often returns a null fileKey; isSameFile still detects hard links.
                if (key == null) key = seen.sameFileIdentity(p, attrs.size());
                if (seen.addObject(key)) bytes += attrs.size();
            }
        }
        return new Stats(files, bytes);
    }

    /**
     * Hard-link identity for providers that report a null {@code fileKey} — Windows, where the
     * only way to tell two links apart is {@link Files#isSameFile}. Same-size files are the
     * candidate set, so the caller gets back the <em>first</em> path of each link group and can
     * dedupe on it like a {@code fileKey}.
     *
     * <p>Two guards keep a store full of same-size blobs from turning {@code jk cache usage} into
     * a quadratic pile of syscalls; both trade a bounded over-count for a bounded cost.
     */
    public static final class SameFileKeys {

        /**
         * Past this many same-size candidates, comparing each new file against all of them costs
         * more than the double-counting it prevents.
         */
        static final int MAX_LINK_CANDIDATES = 64;

        private final Map<Long, List<Path>> bySize = new HashMap<>();

        /** Identity of {@code path}: the first same-size path it is a hard link of, else itself. */
        public Object identity(Path path, long size) {
            // An empty file shares no blob, so dedupe would buy nothing for the comparisons it costs.
            if (size == 0) return path;
            List<Path> candidates = bySize.computeIfAbsent(size, k -> new ArrayList<>(2));
            for (Path candidate : candidates) {
                try {
                    if (Files.isSameFile(path, candidate)) return candidate;
                } catch (IOException vanished) {
                    // A candidate deleted mid-walk cannot be this file's twin.
                }
            }
            if (candidates.size() < MAX_LINK_CANDIDATES) candidates.add(path);
            return path;
        }
    }

    /**
     * Cross-root seen-set: a primitive open-addressed {@code (dev, ino)} long set on unix,
     * an object set of {@code fileKey}s elsewhere, and a {@link SameFileKeys} identity when
     * {@code fileKey} is null (Windows).
     */
    /** A {@code unix:*} attribute the view always reports; the map is total for a regular file. */
    private static long attr(Map<String, Object> unixAttributes, String name) {
        return ((Number) Objects.requireNonNull(unixAttributes.get(name), name)).longValue();
    }

    private static final class SeenLinks {
        boolean unixSupported = true;
        private @Nullable Set<Object> objects; // fallback platforms only, lazily created
        private @Nullable SameFileKeys sameFile; // null-fileKey platforms only, lazily created
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
            Set<Object> set = objects;
            if (set == null) objects = set = new HashSet<>();
            return set.add(key);
        }

        Object sameFileIdentity(Path path, long size) {
            SameFileKeys keys = sameFile;
            if (keys == null) sameFile = keys = new SameFileKeys();
            return keys.identity(path, size);
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
