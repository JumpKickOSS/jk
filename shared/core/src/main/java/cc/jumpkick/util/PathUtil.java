// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.io.IOException;
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
     * Best-effort recursive delete (children first). Swallows {@link IOException}; null/missing
     * root is a no-op.
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
        } catch (IOException ignored) {
        }
    }

    /** As {@link #deleteRecursively}, but a failed delete propagates instead of being swallowed. */
    public static void deleteRecursivelyOrThrow(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            var paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) Files.deleteIfExists(p);
        }
    }
}
