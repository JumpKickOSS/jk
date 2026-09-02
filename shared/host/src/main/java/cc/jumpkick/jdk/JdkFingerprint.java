// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Os;
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
 * Two things a JDK install is asked about: <em>where is its launcher</em> and <em>what is in it</em>.
 *
 * <p><b>Launcher.</b> {@link #java(Path)} / {@link #javac(Path)} / {@link #tool(Path, String)} are
 * the only sanctioned spelling of {@code <javaHome>/bin/<tool>}, because that path carries a
 * {@code .exe} on Windows and a hand-built copy always forgets it (guard G1).
 *
 * <p><b>Fingerprint.</b> {@link #compute(Path)} is a SHA-256 of an installed tool tree — a JDK
 * (including {@code JdkOwnership.MARKER}), or any tool home {@code jk doctor} verifies. Sorted
 * {@code <relpath>\0<file-sha256>\n} lines, then SHA-256 of that manifest, streaming each file so
 * {@code lib/modules} never sits in memory.
 *
 * <p><b>Why this is on the host leaf</b> ({@code shared/host}, package {@code cc.jumpkick.jdk} —
 * "reach before discipline"). The launcher path is needed by everything that forks a JVM, and three
 * of those callers — {@code TaskExec} in {@code :plugin-sdk}, {@code plugins/android} and
 * {@code plugins/image-builder} — cannot see {@code :toolchain-jdk}, which {@code api}-depends on
 * {@code :core} and so would drag tomlj onto a thin worker's classpath. Two of the three were the
 * live Windows bugs. The package name is kept so the move costs no import churn; the rest of
 * {@code cc.jumpkick.jdk} (inventory, registry, resolution) stays in {@code :toolchain-jdk}. Nothing
 * here reaches past the JDK and {@code cc.jumpkick.host}, which is what the leaf's rule requires.
 */
public final class JdkFingerprint {

    private JdkFingerprint() {}

    /**
     * SHA-256 of the empty manifest — what {@link #compute} returns for a tool home holding no
     * regular files. A caller that gets this back hashed <em>nothing</em>, and that is worth saying
     * out loud rather than printing as a digest: until it was the only value
     * {@code jk doctor --verify-linked} could ever produce, because a symlinked root was handed to
     * {@code visitFile} and rejected there. Named so a test can assert it deliberately and a caller
     * can tell "empty tree" from "this tree hashes to X".
     */
    public static final String EMPTY_TREE = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /**
     * Fingerprint of {@code installDir}. Relative paths use {@code /} even on Windows so the digest
     * is independent of the host separator.
     *
     * <p>The root is resolved with {@link Path#toRealPath} first, so a symlinked tool home
     * fingerprints the tree it points at and {@code compute(link)} equals {@code compute(target)} —
     * that is the whole of {@code jk doctor --verify-linked}, and jk symlinks a tool home whenever
     * it discovers a host install instead of downloading one. Symlinks <em>inside</em> the tree are
     * still not followed: the pointer is not the install, and hashing through one would make the
     * digest depend on a tree jk does not own.
     */
    public static String compute(Path installDir) throws IOException {
        Path root = installDir.toRealPath();
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Do not descend through a symlink / junction; the pointer is not the install.
                if (!dir.equals(root) && Files.isSymbolicLink(dir)) {
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
        files.sort(Comparator.comparing(p -> rel(root, p)));

        StringBuilder sb = new StringBuilder(files.size() * 96);
        for (Path file : files) {
            sb.append(rel(root, file))
                    .append('\0')
                    .append(Hashing.sha256Hex(file))
                    .append('\n');
        }
        return Hashing.sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The on-disk file name of a JDK tool on this host: {@code javac}, or {@code javac.exe} on
     * Windows. The one place the suffix rule is written down — guard G1 bans re-deriving it.
     */
    public static String toolName(String tool) {
        return Os.isWindows() ? tool + ".exe" : tool;
    }

    /**
     * {@code <javaHome>/bin/<tool>} (with the Windows suffix). {@code tool} is any JDK launcher —
     * a plugin worker names one it was configured with, so this takes the name rather than
     * enumerating them.
     */
    public static Path tool(Path javaHome, String tool) {
        return javaHome.resolve("bin").resolve(toolName(tool));
    }

    /**
     * {@code bin/javac} (or {@code javac.exe}) under {@code javaHome}, used as the inventory
     * {@code touched} signal.
     */
    public static Path javac(Path javaHome) {
        return tool(javaHome, "javac");
    }

    /** {@code bin/java} (or {@code java.exe}) under {@code javaHome}. */
    public static Path java(Path javaHome) {
        return tool(javaHome, "java");
    }

    /**
     * True when {@code dir} is a {@code StableJdkPointer} alias rather than the install itself: a
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
