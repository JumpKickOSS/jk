// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Directory pointer: POSIX symlink, Windows junction ({@code mklink /J}, no elevation). Used for
 * stable JDK homes and default/current pointers.
 */
public final class DirLinks {

    private DirLinks() {}

    /** Replace {@code link} with a pointer at directory {@code target}. */
    public static void replace(Path link, Path target) throws IOException {
        Files.createDirectories(link.getParent());
        Files.deleteIfExists(link);
        if (HostPlatform.isWindows()) {
            createJunction(link, target);
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    /**
     * True when {@code path} is a POSIX symlink or a Windows junction/reparse point that resolves.
     * {@link Files#isSymbolicLink} is false for junctions — do not use it as the cross-platform check.
     */
    static boolean isLink(Path path) throws IOException {
        if (!Files.exists(path)) return false;
        if (Files.isSymbolicLink(path)) return true;
        if (!HostPlatform.isWindows()) return false;
        // Junctions are directories with a reparse point; they resolve to a different real path.
        if (!Files.isDirectory(path)) return false;
        try {
            return !path.toRealPath().equals(path.toAbsolutePath().normalize());
        } catch (IOException e) {
            return false;
        }
    }

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
}
