// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

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

    /** Windows briefly denies REPLACE when another handle still has the target open. */
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
     */
    public static void moveInto(Path tmp, Path target) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MOVE_ATTEMPTS; attempt++) {
            try {
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException e) {
                last = e;
                if (attempt == MOVE_ATTEMPTS) break;
                sleepBriefly(attempt);
            }
        }
        throw last;
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
            Thread.sleep(Math.min(50L, 5L * attempt));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
