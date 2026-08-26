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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
     * Whether {@code file} is something this host can execute.
     *
     * <p><strong>Not {@link Files#isExecutable}.</strong> That is the most expensive filesystem
     * predicate jk uses — measured at 33.4&nbsp;µs on Windows against 0.52 on Linux, a
     * <strong>64×</strong> gap — because the JDK implements EXECUTE access there as a
     * security-descriptor read plus an {@code AccessCheck}. Windows has no executable bit; what
     * decides whether a file runs is its extension, so the access check answers an expensive
     * question nobody asked. Thirteen call sites paid it, two of them per entry of a directory
     * listing (JK-1030).
     *
     * <p>So: on Windows, a regular file whose extension is in {@code PATHEXT} (defaulted when the
     * variable is unset or empty, which it is inside a stripped service environment). Elsewhere the
     * real access check, which is cheap and is the only correct answer.
     *
     * <p>Callers probing a <em>named</em> tool should still test the name themselves and reach here
     * last; this collapses one predicate, it does not reorder a caller's chain.
     */
    public static boolean isRunnable(Path file) {
        if (!Os.isWindows()) return Files.isExecutable(file);
        if (!Files.isRegularFile(file)) return false;
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false; // no extension: Windows will not run it
        String ext = name.substring(dot).toLowerCase(Locale.ROOT);
        for (String candidate : windowsExecutableExtensions()) {
            if (candidate.equals(ext)) return true;
        }
        return false;
    }

    /**
     * {@code PATHEXT}, lowercased and split, or the Windows default set when it is unset or blank.
     * Read once — {@code PATHEXT} cannot change inside one invocation, and thirteen callers probing
     * three names each over a fifty-entry {@code PATH} re-read it 150 times.
     */
    private static List<String> windowsExecutableExtensions() {
        List<String> memo = pathExt;
        if (memo != null) return memo;
        String raw = System.getenv("PATHEXT");
        List<String> parsed = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split(";")) {
                String t = part.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) parsed.add(t.startsWith(".") ? t : "." + t);
            }
        }
        if (parsed.isEmpty()) {
            parsed = List.of(".com", ".exe", ".bat", ".cmd", ".vbs", ".js", ".ws", ".msc", ".ps1");
        }
        pathExt = List.copyOf(parsed);
        return pathExt;
    }

    /** Memoized {@code PATHEXT}; see {@link #windowsExecutableExtensions()}. */
    private static volatile List<String> pathExt;

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
     *
     * <p>A failure does not stop the walk. One undeletable file must not strand its deletable
     * siblings — {@code jk clean}'s report is the tally, and the tally has to match what actually
     * came off disk. The caller gets the first failure with any later ones attached as suppressed;
     * finishing the walk is also what makes the tally independent of traversal order.
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
            IOException e = deleteOne(root, rootAttrs, tally);
            if (e != null && !quiet && !(e instanceof NoSuchFileException)) throw e;
            return;
        }
        // No FileVisitOption.FOLLOW_LINKS, so a link to a directory arrives at visitFile and is
        // removed as a leaf. Do not add it.
        // Collected, not thrown, so the walk finishes: see the note on the tally above.
        IOException[] failure = {null};
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                record(deleteOne(file, attrs, tally));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                record(e);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                // `e` is a failure to iterate the directory, not to delete it, so still try the
                // delete afterwards: an unreadable directory may well be removable.
                record(e);
                record(deleteOne(dir, null, tally));
                return FileVisitResult.CONTINUE;
            }

            private void record(IOException e) {
                // A vanished entry is not a failure — another process finished the job for us.
                if (e == null || quiet || e instanceof NoSuchFileException) return;
                if (failure[0] == null) failure[0] = e;
                else if (failure[0] != e) failure[0].addSuppressed(e);
            }
        });
        if (failure[0] != null) throw failure[0];
    }

    /**
     * One entry, never its target; returns the failure instead of throwing it, so the caller can
     * keep walking. {@code attrs} comes from the walk (already NOFOLLOW) so the tally sizes the
     * link and not what it points at; null means a directory, which counts as zero.
     */
    private static IOException deleteOne(Path p, BasicFileAttributes attrs, Removed tally) {
        try {
            boolean gone = Files.deleteIfExists(p);
            if (gone && tally != null && attrs != null && attrs.isRegularFile()) {
                tally.add(attrs.size());
            }
            return null;
        } catch (IOException e) {
            return e;
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
