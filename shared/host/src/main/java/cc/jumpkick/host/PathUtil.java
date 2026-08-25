// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.host;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

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
     * Best-effort recursive delete (children first). Swallows every I/O failure; null/missing root
     * is a no-op.
     *
     * <p>{@link UncheckedIOException} is caught as well as {@link IOException}, and that is not
     * defensive padding: {@code Files.walk}'s traversal is <em>lazy</em>, so a directory entry that
     * disappears between the walk starting and the stream reaching it surfaces from
     * {@code FileTreeIterator} as an {@code UncheckedIOException}, not an {@code IOException}. That
     * happens routinely here — the roots this deletes are engine sockets, pid files and worker
     * scratch that a daemon may still be tearing down concurrently. Catching only the checked half
     * turns another process's normal cleanup into an intermittent failure in ours.
     */
    public static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException | UncheckedIOException ignored) {
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
     */
    public static void deleteRecursivelyOrThrow(Path root, Removed tally) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            // Reverse lexicographic order visits every child before its parent: a child's path is
            // the parent's plus a separator, so it always sorts after it.
            var paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                // Size first — a deleted file has none left to ask for — and count only a delete
                // that actually happened.
                long size = Files.isRegularFile(p) ? Files.size(p) : -1;
                if (Files.deleteIfExists(p) && size >= 0) tally.add(size);
            }
        } catch (UncheckedIOException e) {
            // Same lazy-walk race as deleteRecursively: an entry that vanishes mid-traversal comes
            // out of FileTreeIterator unchecked. This method's whole contract is `throws
            // IOException`, so hand the caller the exception type it declares rather than an
            // unchecked one it has no reason to catch.
            throw e.getCause() == null ? new IOException(e.getMessage(), e) : e.getCause();
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
