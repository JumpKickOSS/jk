// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cache;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
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

    /**
     * One walk of {@code root}: its {@link Stats} first, then the files under each of {@code
     * within} (each a subtree of {@code root}) as a count. The same tree walked once per question
     * cost {@code jk status} three passes over the cache where one answers all three.
     */
    public static long[] sections(Path root, List<Path> within) throws IOException {
        long[] out = new long[2 + within.size()];
        if (root == null || !Files.isDirectory(root)) return out;
        SeenLinks seen = new SeenLinks();
        PathUtil.forEachRegularFile(root, (p, attrs) -> {
            long size = regularSize(p, attrs, seen);
            if (size < 0) return;
            out[0]++;
            out[1] += size;
            for (int i = 0; i < within.size(); i++) {
                if (p.startsWith(within.get(i))) out[2 + i]++;
            }
        });
        return out;
    }

    /**
     * {@code p}'s bytes when it is a regular file whose inode this walk has not met, 0 when it has,
     * and -1 when it is not a regular file or will not read.
     */
    private static long regularSize(Path p, BasicFileAttributes attrs, SeenLinks seen) {
        if (seen.unixSupported) {
            try {
                Map<String, Object> u = Files.readAttributes(p, "unix:isRegularFile,nlink,size,dev,ino");
                if (!Boolean.TRUE.equals(u.get("isRegularFile"))) return -1;
                long nlink = attr(u, "nlink");
                return nlink <= 1 || seen.addInode(attr(u, "dev"), attr(u, "ino")) ? attr(u, "size") : 0;
            } catch (UnsupportedOperationException | IllegalArgumentException e) {
                seen.unixSupported = false;
            } catch (IOException unreadable) {
                return -1;
            }
        }
        if (!attrs.isRegularFile()) return -1;
        Object key = attrs.fileKey();
        if (key == null) key = seen.sameFileIdentity(p, attrs.size(), attrs.lastModifiedTime());
        return seen.addObject(key) ? attrs.size() : 0;
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
        long[] totals = {0, 0};
        PathUtil.forEachRegularFile(dir, (p, attrs) -> {
            long size = regularSize(p, attrs, seen);
            if (size >= 0) {
                totals[0]++;
                totals[1] += size;
            }
        });
        return new Stats(totals[0], totals[1]);
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

        private final Map<List<Object>, List<Path>> byShape = new HashMap<>();

        /**
         * Identity of {@code path}: the first path of the same size and modification time it is a
         * hard link of, else itself. Links are one file, so they share both; bucketing on the pair
         * keeps a store full of same-size files from being compared pairwise.
         */
        public Object identity(Path path, long size, FileTime modified) {
            // An empty file shares no blob, so dedupe would buy nothing for the comparisons it costs.
            if (size == 0) return path;
            List<Path> candidates = byShape.computeIfAbsent(List.of(size, modified), k -> new ArrayList<>(2));
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

        Object sameFileIdentity(Path path, long size, FileTime modified) {
            SameFileKeys keys = sameFile;
            if (keys == null) sameFile = keys = new SameFileKeys();
            return keys.identity(path, size, modified);
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
