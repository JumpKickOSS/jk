// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.jdk;

import cc.jumpkick.host.Os;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Directory pointer: POSIX symlink, Windows junction ({@code mklink /J}, no elevation). Used for the
 * stable {@code <vendor>-<major>} JDK alias and the Android SDK pointer.
 */
public final class DirLinks {

    private DirLinks() {}

    /**
     * Point {@code link} at directory {@code target}, creating {@code link}'s parent and removing
     * any existing pointer of that name first. Only an unpopulated {@code link} is removed here —
     * a caller that may be replacing a real directory must clear it itself.
     */
    public static void replace(Path link, Path target) throws IOException {
        Files.createDirectories(link.getParent());
        Files.deleteIfExists(link);
        if (Os.isWindows()) {
            createJunction(link, target);
        } else {
            Files.createSymbolicLink(link, target);
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
