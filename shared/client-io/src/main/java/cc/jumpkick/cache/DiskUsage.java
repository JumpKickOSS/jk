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
 * <p>Only files with {@code nlink > 1} are tracked at all (a single-link file cannot be met twice,
 * and is plainly the walk's own). A tracked inode counts once, under the first root it turns up
 * in, and only when every one of its links turns up in the walk: an inode another tree also links
 * ({@code ~/.m2}, a project's {@code target/}, a private home) is reported as shared, not owned.
 * Windows reads the link count and file identity through {@link WindowsFileIdentity}; a file it
 * cannot read is counted as owned through the {@code fileKey} / {@link SameFileKeys} fallback.
 */
public final class DiskUsage {

    private DiskUsage() {}

    /**
     * File count (directory entries), the unique bytes this walk owns, and the bytes it found but
     * does not own: an inode with a link outside the walked trees ({@code ~/.m2}, a project's
     * {@code target/}, another home) belongs to whoever holds that link until its link count is all
     * the walk's own, so its bytes are {@code sharedBytes}, never {@code bytes}.
     */
    public record Stats(long files, long bytes, long sharedBytes) {
        public Stats(long files, long bytes) {
            this(files, bytes, 0L);
        }
    }

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
        long[][] totals = new long[roots.size()][];
        for (int i = 0; i < roots.size(); i++) {
            totals[i] = walkExclusive(roots.get(i), seen, i);
        }
        long[] shared = seen.unowned(roots.size());
        Stats[] out = new Stats[roots.size()];
        for (int i = 0; i < roots.size(); i++) {
            out[i] = new Stats(totals[i][0], totals[i][1] - shared[i], shared[i]);
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
            long size = regularSize(p, attrs, seen, 0);
            if (size < 0) return;
            out[0]++;
            out[1] += size;
            for (int i = 0; i < within.size(); i++) {
                if (p.startsWith(within.get(i))) out[2 + i]++;
            }
        });
        out[1] -= seen.unowned(1)[0];
        return out;
    }

    /**
     * {@code p}'s bytes when it is a regular file whose inode this walk has not met, 0 when it has,
     * and -1 when it is not a regular file or will not read. A multi-link inode is recorded against
     * {@code root}, the walk's root it was first met under, so {@link SeenLinks#unowned} can take its
     * bytes back when not all of its links turn up.
     */
    private static long regularSize(Path p, BasicFileAttributes attrs, SeenLinks seen, int root) {
        if (seen.unixSupported) {
            try {
                Map<String, Object> u = Files.readAttributes(p, "unix:isRegularFile,nlink,size,dev,ino");
                if (!Boolean.TRUE.equals(u.get("isRegularFile"))) return -1;
                long nlink = attr(u, "nlink");
                long size = attr(u, "size");
                if (nlink <= 1) return size;
                return seen.link(attr(u, "dev") * 0x9E3779B97F4A7C15L + attr(u, "ino"), nlink, size, root);
            } catch (UnsupportedOperationException | IllegalArgumentException e) {
                seen.unixSupported = false;
            } catch (IOException unreadable) {
                return -1;
            }
        }
        if (!attrs.isRegularFile()) return -1;
        // Windows: NTFS keeps a link count and a file index that no attribute view reports.
        WindowsFileIdentity.Info win = seen.windowsSupported ? WindowsFileIdentity.read(p) : null;
        if (win != null) {
            if (win.links() <= 1) return attrs.size();
            return seen.link(win.volume() * 0x9E3779B97F4A7C15L + win.index(), win.links(), attrs.size(), root);
        }
        seen.windowsSupported = false;
        Object key = attrs.fileKey();
        if (key == null) key = seen.sameFileIdentity(p, attrs.size(), attrs.lastModifiedTime());
        return seen.addObject(key) ? attrs.size() : 0;
    }

    /**
     * How many directory entries link to {@code file}: the unix link count, or NTFS's on Windows;
     * 1 when the platform will not say, which treats the file as its one holder's.
     */
    public static long linkCount(Path file) {
        try {
            Object n = Files.getAttribute(file, "unix:nlink");
            if (n instanceof Number count) return count.longValue();
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException notUnix) {
            // fall through to Windows
        }
        WindowsFileIdentity.Info win = WindowsFileIdentity.read(file);
        return win == null ? 1L : Math.max(1L, win.links());
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

    private static long[] walkExclusive(Path dir, SeenLinks seen, int root) throws IOException {
        long[] totals = {0, 0};
        if (dir == null || !Files.isDirectory(dir)) return totals;
        PathUtil.forEachRegularFile(dir, (p, attrs) -> {
            long size = regularSize(p, attrs, seen, root);
            if (size >= 0) {
                totals[0]++;
                totals[1] += size;
            }
        });
        return totals;
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
        boolean windowsSupported = true;

        /** Per multi-link inode met: {root it was first met under, its size, its link count, links met}. */
        private final Map<Long, long[]> links = new HashMap<>();

        /**
         * Meet one link of inode {@code key}: its {@code size} the first time, 0 after, recording
         * how many of its {@code nlink} links this walk finds.
         */
        long link(long key, long nlink, long size, int root) {
            long[] seenLink = links.get(key);
            if (seenLink == null) {
                links.put(key, new long[] {root, size, nlink, 1});
                return size;
            }
            seenLink[3]++;
            return 0;
        }

        /**
         * Per root, the bytes of the inodes first met under it whose links did not all turn up in
         * the walk: another tree holds them, so they are not the walk's to count.
         */
        long[] unowned(int roots) {
            long[] out = new long[roots];
            for (long[] l : links.values()) {
                if (l[3] < l[2]) out[(int) l[0]] += l[1];
            }
            return out;
        }

        private @Nullable Set<Object> objects; // fallback platforms only, lazily created
        private @Nullable SameFileKeys sameFile; // null-fileKey platforms only, lazily created

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
    }
}
