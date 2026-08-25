// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Shared filesystem helpers. */
public final class PathUtil {

    private PathUtil() {}

    /**
     * Resolve a user-typed workspace/project path to an absolute, normalized {@link Path}.
     *
     * <ul>
     *   <li>{@code ~} and {@code ~/…} (or {@code ~\} on Windows) expand via {@code user.home}
     *   <li>relative paths resolve against {@code user.home} (engine CWD is not the home dir)
     *   <li>absolute paths are normalized as-is
     * </ul>
     *
     * <p>So {@code src/oss/jk} and {@code ~/src/oss/jk} both become {@code $HOME/src/oss/jk}.
     *
     * @throws IllegalArgumentException if {@code raw} is null/blank
     */
    public static Path resolveUserPath(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("path is empty");
        }
        String s = raw.strip();
        Path home = userHome();
        if (s.equals("~")) {
            return home;
        }
        // Only the shell-style home prefix — "~other" is not expanded (not a multi-user lookup).
        if (s.startsWith("~/") || s.startsWith("~\\")) {
            String rest = s.substring(2);
            return rest.isEmpty() ? home : home.resolve(rest).normalize();
        }
        Path p = Path.of(s);
        if (p.isAbsolute()) {
            return p.normalize();
        }
        return home.resolve(p).normalize();
    }

    /** {@code user.home} as an absolute normalized path (falls back to {@code user.dir}). */
    public static Path userHome() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            home = System.getProperty("user.dir", ".");
        }
        return Path.of(home).toAbsolutePath().normalize();
    }

    /**
     * Best-effort recursive delete. Swallows every I/O failure; a null or absent root is a no-op.
     *
     * <p><b>A symbolic link is removed, never entered.</b> That holds wherever the link turns up —
     * as the root, or anywhere inside the tree — and it is the reason this is written against
     * {@link Files#walkFileTree} rather than {@code Files.walk}. Both refuse to follow links by
     * default, but with the stream the rule is invisible: it lives in an omitted
     * {@code FileVisitOption}, so "cleanup left something behind" reads like a missing
     * {@code FOLLOW_LINKS} and one added enum constant turns this method into something that
     * deletes other people's files. Here the rule is a line of code in {@link #deleteOne} with a
     * test on it.
     *
     * <p>What this cannot defend against is a <em>caller</em> that hands over a path already
     * routed through a link — {@code <link>/sub} names a real directory, and no delete can tell it
     * from any other. Containment checks belong on the caller's side and have to compare real
     * paths, not string prefixes.
     */
    public static void deleteRecursively(Path root) {
        try {
            deleteTree(root, null, true);
        } catch (IOException quietNeverThrows) {
            throw new AssertionError(quietNeverThrows);
        }
    }

    /** As {@link #deleteRecursively}, but a failed delete propagates instead of being swallowed. */
    public static void deleteRecursivelyOrThrow(Path root) throws IOException {
        deleteRecursivelyOrThrow(root, new Removed());
    }

    /**
     * As {@link #deleteRecursivelyOrThrow(Path)}, tallying what came off disk — what {@code jk
     * clean} prints. The tally is mutable on purpose: a delete that fails part-way still removed
     * everything it got to, and the caller's report has to say so.
     *
     * <p>A link counts as itself, not as its target: the bytes reclaimed by unlinking are the
     * link's own, and a report that added the target's size would claim space that is still in use.
     */
    public static void deleteRecursivelyOrThrow(Path root, Removed tally) throws IOException {
        deleteTree(root, tally, false);
    }

    /**
     * Children first, then the directory. {@code quiet} decides whether a failure is swallowed or
     * handed to the caller; a vanished entry is success either way, because the roots this deletes
     * are engine sockets, pid files and worker scratch that another process may be tearing down at
     * the same time, and losing a race to it is not a failure of ours.
     */
    private static void deleteTree(Path root, Removed tally, boolean quiet) throws IOException {
        if (root == null) return;
        BasicFileAttributes rootAttrs;
        try {
            // NOFOLLOW: Files.exists() would follow, so a DANGLING link answered "absent" and this
            // method returned without removing it — the one case where it failed to honour its own
            // "remove the link" rule.
            rootAttrs = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return;
        } catch (IOException e) {
            if (quiet) return;
            throw e;
        }
        // A link (or any non-directory) is one delete, whatever it points at.
        if (!rootAttrs.isDirectory()) {
            deleteOne(root, rootAttrs, tally, quiet);
            return;
        }
        // No FileVisitOption.FOLLOW_LINKS, so a link to a directory arrives at visitFile and is
        // removed as a leaf. Do not add it.
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                deleteOne(file, attrs, tally, quiet);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                if (quiet || e instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
                throw e;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null && !quiet && !(e instanceof NoSuchFileException)) throw e;
                deleteOne(dir, null, tally, quiet);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * One entry, never its target. {@code attrs} comes from the walk (already NOFOLLOW) so the
     * tally sizes the link and not what it points at; null means a directory, which counts as zero.
     */
    private static void deleteOne(Path p, BasicFileAttributes attrs, Removed tally, boolean quiet) throws IOException {
        try {
            boolean gone = Files.deleteIfExists(p);
            if (gone && tally != null && attrs != null && attrs.isRegularFile()) {
                tally.add(attrs.size());
            }
        } catch (IOException e) {
            if (!quiet) throw e;
        }
    }

    /** Running tally for {@link #deleteRecursivelyOrThrow(Path, Removed)}. Directories count as 0 files. */
    public static final class Removed {
        private long files;
        private long bytes;

        public long files() {
            return files;
        }

        public long bytes() {
            return bytes;
        }

        void add(long size) {
            files++;
            bytes += size;
        }
    }
}
