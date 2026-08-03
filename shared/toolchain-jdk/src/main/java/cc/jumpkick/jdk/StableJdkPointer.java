// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Stable {@code <vendor>-<major>} handle under {@link JkDirs#jdks()} (symlink; Windows junction) that
 * tracks the current patch install so IDE {@code homePath}s survive point-release upgrades. Superseded
 * dirs go to {@link JdkGarbage}; {@link #javaHome} does not resolve the link.
 */
public final class StableJdkPointer {

    private static final boolean WINDOWS = HostPlatform.isWindows();

    private final Path jdksRoot;

    public StableJdkPointer(Path jdksRoot) {
        this.jdksRoot = Objects.requireNonNull(jdksRoot, "jdksRoot");
    }

    /** Pointer rooted at jk's default managed JDK directory. */
    public static StableJdkPointer atDefaultRoot() {
        return new StableJdkPointer(JkDirs.jdks());
    }

    /**
     * {@code <jdksRoot>/<pointerName>} — the pointer path (link or, degenerate, the install itself).
     */
    public Path pointerDir(String pointerName) {
        return jdksRoot.resolve(pointerName);
    }

    /**
     * Stable {@code JAVA_HOME} for the pointer, resolving the macOS {@code Contents/Home} bundle
     * layout but <em>not</em> the link itself, so the returned path stays {@code
     * <jdksRoot>/<pointerName>[/Contents/Home]}.
     */
    public Path javaHome(String pointerName) {
        return IntellijJdkDir.javaHome(pointerDir(pointerName));
    }

    /**
     * Ensure {@code <jdksRoot>/<pointerName>} resolves to {@code installDir}. Idempotent: a no-op
     * when the link already resolves there, or when the pointer name already <em>is</em> the install
     * dir (degenerate case where a vendor's version equals its major, e.g. a bare {@code
     * graalvm-25}). A no-op when {@code installDir} doesn't exist.
     */
    public void ensure(String pointerName, Path installDir) throws IOException {
        Objects.requireNonNull(pointerName, "pointerName");
        Objects.requireNonNull(installDir, "installDir");
        if (!Files.exists(installDir)) return;

        Path pointer = jdksRoot.resolve(pointerName);
        Path canonicalInstall = installDir.toRealPath();

        if (Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) {
            try {
                // toRealPath resolves a symlink OR a Windows junction; if it
                // already lands on the install (or IS the install dir), done.
                if (pointer.toRealPath().equals(canonicalInstall)) return;
            } catch (IOException dangling) {
                // fall through and recreate
            }
            removeExisting(pointer);
        }

        Files.createDirectories(jdksRoot);
        link(pointer, installDir);
    }

    private static void link(Path pointer, Path target) throws IOException {
        if (WINDOWS) {
            createJunction(pointer, target);
        } else {
            Files.createSymbolicLink(pointer, target);
        }
    }

    /**
     * Create a Windows directory junction {@code pointer → target} via {@code mklink /J} (a cmd.exe
     * builtin). Junctions need no elevation.
     */
    private static void createJunction(Path pointer, Path target) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "cmd.exe",
                "/c",
                "mklink",
                "/J",
                pointer.toAbsolutePath().toString(),
                target.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        try {
            Process proc = pb.start();
            byte[] out = proc.getInputStream().readAllBytes();
            if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IOException("mklink /J timed out creating " + pointer);
            }
            if (proc.exitValue() != 0) {
                throw new IOException("mklink /J failed (exit "
                        + proc.exitValue()
                        + "): "
                        + new String(out, StandardCharsets.UTF_8).trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted creating junction " + pointer, e);
        }
    }

    /**
     * Remove a pointer whether it's a POSIX symlink, a Windows junction, or a real directory —
     * without ever recursing <em>through</em> a link.
     *
     * <p>{@link Files#delete} removes a symlink, a junction, or an empty dir in one shot and never
     * follows the link, so a junction's target is untouched. Only a genuinely populated directory
     * reaches the recursive branch (a junction never does — {@code delete} succeeds on it first).
     * This is the load-bearing invariant: {@link Files#walk} would descend into a junction (Java
     * doesn't classify junctions as symlinks) and delete the real JDK.
     */
    private static void removeExisting(Path pointer) throws IOException {
        try {
            Files.delete(pointer);
        } catch (NoSuchFileException gone) {
            // already removed
        } catch (DirectoryNotEmptyException realDir) {
            try (Stream<Path> walk = Files.walk(pointer)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }
}
