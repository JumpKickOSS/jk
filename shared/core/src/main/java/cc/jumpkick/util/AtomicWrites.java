// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import cc.jumpkick.host.Os;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Atomic write via temp sibling + move ({@code REPLACE_EXISTING} fallback). A crash leaves a
 * {@code .tmp} sibling, never a torn target.
 */
public final class AtomicWrites {

    /**
     * Attempts before a denied replace is final. Windows briefly denies REPLACE while another handle
     * still has the target open; the back-off grows 5&nbsp;ms per attempt, so seven waits is ~140 ms.
     */
    private static final int MOVE_ATTEMPTS = 8;

    private AtomicWrites() {}

    /** Write {@code bytes} to {@code target} atomically. */
    public static void replace(Path target, byte[] bytes) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, "." + target.getFileName() + "-", ".tmp");
        try {
            Files.write(tmp, bytes);
            moveInto(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Write {@code content} (UTF-8) to {@code target} atomically. */
    public static void replace(Path target, String content) throws IOException {
        replace(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Move a fully-written temp file over {@code target} atomically ({@code REPLACE_EXISTING}
     * fallback). The temp file must live in {@code target}'s directory.
     *
     * <p>On Windows only, a denied replace is retried for up to ~140&nbsp;ms: another handle on the
     * target makes the rename fail until it closes. A POSIX denial throws on the first attempt.
     */
    public static void moveInto(Path tmp, Path target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException e) {
                // A POSIX EACCES is permanent; only Windows' transient sharing denial is worth waiting out.
                if (!Os.isWindows() || attempt == MOVE_ATTEMPTS) throw e;
                sleepBriefly(attempt);
            }
        }
    }

    /**
     * Atomically install {@code staging} as {@code target}, which must not already exist (no
     * {@code REPLACE_EXISTING} — concurrent publish races surface as exists/not-empty errors).
     * {@code staging} must be on {@code target}'s filesystem.
     */
    public static void publishDir(Path staging, Path target) throws IOException {
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staging, target);
        }
    }

    private static void sleepBriefly(int attempt) {
        try {
            Thread.sleep(5L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
