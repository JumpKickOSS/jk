// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.host.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * SHA-256 fingerprint of an installed tool tree — a JDK (including {@link JdkOwnership#MARKER}), or
 * any tool home {@code jk doctor} verifies. Sorted {@code <relpath>\0<file-sha256>\n} lines, then
 * SHA-256 of that manifest, streaming each file so {@code lib/modules} never sits in memory. Does
 * not follow symlinks.
 */
public final class JdkFingerprint {

    private JdkFingerprint() {}

    /**
     * Fingerprint of {@code installDir}. Relative paths use {@code /} even on Windows so the digest
     * is independent of the host separator.
     */
    public static String compute(Path installDir) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(installDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Do not descend through a symlink / junction; the pointer is not the install.
                if (!dir.equals(installDir) && Files.isSymbolicLink(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && !Files.isSymbolicLink(file)) files.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                throw new IOException("jdk fingerprint: unreadable " + file, exc);
            }
        });
        files.sort(Comparator.comparing(p -> rel(installDir, p)));

        StringBuilder sb = new StringBuilder(files.size() * 96);
        for (Path file : files) {
            sb.append(rel(installDir, file))
                    .append('\0')
                    .append(Hashing.sha256Hex(file))
                    .append('\n');
        }
        return Hashing.sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code bin/javac} (or {@code javac.exe}) under {@code javaHome}, used as the inventory
     * {@code touched} signal.
     */
    public static Path javac(Path javaHome) {
        String exe = HostPlatform.isWindows() ? "javac.exe" : "javac";
        return javaHome.resolve("bin").resolve(exe);
    }

    /** {@code bin/java} (or {@code java.exe}) under {@code javaHome}. */
    public static Path java(Path javaHome) {
        String exe = HostPlatform.isWindows() ? "java.exe" : "java";
        return javaHome.resolve("bin").resolve(exe);
    }

    /**
     * True when {@code dir} is a {@link StableJdkPointer} alias rather than the install itself: a
     * POSIX symlink, or a directory whose resolved name differs from the name on disk (Windows
     * junction {@code graalvm-25} → {@code graalvm-25.0.4}). Parent-path symlink noise ({@code /var}
     * → {@code /private/var} on macOS) does not trip this — only the final name is compared.
     */
    public static boolean isAliasDir(Path dir) {
        if (dir == null) return false;
        if (Files.isSymbolicLink(dir)) return true;
        try {
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return false;
            Path name = dir.getFileName();
            Path realName = dir.toRealPath().getFileName();
            return name != null && realName != null && !name.equals(realName);
        } catch (IOException e) {
            return false;
        }
    }

    private static String rel(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
