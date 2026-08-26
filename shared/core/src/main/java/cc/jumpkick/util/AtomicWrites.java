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
import java.util.function.IntConsumer;

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

    /**
     * Write {@code bytes} to {@code target} atomically.
     *
     * <p>The cleanup is on the failure path, not in a {@code finally}: a successful
     * {@link #moveInto} has already consumed {@code tmp}, so a {@code finally} unlink was an
     * unlink of a path that could not exist — one wasted metadata call on every success, at
     * fifty call sites and once per CAS blob (JK-1029). On NTFS that op is ~11&nbsp;µs against
     * Linux's ~1.5.
     */
    public static void replace(Path target, byte[] bytes) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, "." + target.getFileName() + "-", ".tmp");
        boolean moved = false;
        try {
            Files.write(tmp, bytes);
            moveInto(tmp, target);
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(tmp);
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
                backOff.accept(attempt);
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

    /**
     * The retry back-off, as a seam. Production always sleeps; {@code AtomicWritesTest} swaps in a
     * counter so it can assert <em>how many times</em> the move was retried instead of how many
     * milliseconds it took.
     *
     * <p>The seam exists because the alternative did not work. "A POSIX denial does not retry" was
     * asserted as {@code elapsedMs < 140} — 140 ms being the sum of the seven back-offs — which on a
     * loaded machine is a coin toss and says nothing about retrying either way. Attempt count is the
     * property; milliseconds were a proxy for it (JK-2446). Package-private, non-final, and never
     * reassigned outside a test.
     */
    static IntConsumer backOff = AtomicWrites::sleepBriefly;

    private static void sleepBriefly(int attempt) {
        try {
            Thread.sleep(5L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
