// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import cc.jumpkick.host.Os;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Write via temp sibling + move ({@code REPLACE_EXISTING} fallback).
 *
 * <p><strong>What this guarantees:</strong> no concurrent reader ever observes a partial target. The
 * rename is atomic with respect to other processes, which is the property nearly every caller here
 * actually wants — another engine reading an action record, a second {@code jk} parsing a lockfile, an
 * IDE tailing a manifest.
 *
 * <p><strong>What it does not guarantee, despite what this comment used to say:</strong> crash
 * consistency. There is no {@code fsync} before the rename — and none anywhere in the product; the
 * only {@link java.nio.channels.FileChannel#force} in the tree is a disk benchmark. So on ext4
 * {@code data=ordered} or APFS, a power loss can make the rename durable while the data blocks are
 * not, leaving a zero-length or truncated target rather than the {@code .tmp} sibling the old wording
 * promised (JK-1037). Fifty call sites read that sentence and trusted it.
 *
 * <p>Use {@link #replaceDurably} for a file where a torn target is not recoverable by re-running.
 * There are four: {@code jk-lock.toml}, the engine install pointer, {@code aot.toml}, and
 * {@code run-number} — that last one because a lost increment lets a later run delete a completed run
 * tree, which is the hazard its flock exists for.
 *
 * <p><strong>And do not reach for it otherwise.</strong> Roughly thirty-five of the fifty call sites
 * write reconstructible, fail-open, advisory data — memos, timings, stamps, metrics — and
 * {@link #replace} is already measured at 372.7&nbsp;µs on Windows against 37.8 on Linux. An
 * {@code fsync} on ~800 advisory writes per build would cost far more than the durability it bought,
 * for data whose loss costs one recomputation. This is the one place in the filesystem sweep where
 * the cheap change is the wrong one.
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
        Path tmp = staging(parent, target);
        boolean moved = false;
        try {
            Files.write(tmp, bytes);
            moveInto(tmp, target);
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(tmp);
        }
    }

    /**
     * As {@link #replace(Path, byte[])}, forcing the bytes to stable storage before the rename.
     *
     * <p>For the handful of files where a torn target is not recoverable by re-running: the lockfile,
     * the engine install pointer, {@code aot.toml}, {@code run-number}. Everything else in this tree
     * is a cache or a hint whose loss costs one recomputation, and paying an {@code fsync} for those
     * would cost more than it protects — see the class javadoc.
     */
    public static void replaceDurably(Path target, byte[] bytes) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = staging(parent, target);
        boolean moved = false;
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.write(ByteBuffer.wrap(bytes));
                // Metadata too: the rename below is only meaningful if the bytes it publishes are
                // already on stable storage.
                ch.force(true);
            }
            moveInto(tmp, target);
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(tmp);
        }
    }

    /** As {@link #replaceDurably(Path, byte[])} for UTF-8 text. */
    public static void replaceDurably(Path target, String content) throws IOException {
        replaceDurably(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Write {@code content} (UTF-8) to {@code target} atomically. */
    public static void replace(Path target, String content) throws IOException {
        replace(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A temp sibling to write into, carrying the mode {@code target} should end up with.
     *
     * <p><strong>Why this is not just {@code createTempFile}.</strong> On POSIX,
     * {@link Files#createTempFile} creates {@code 0600} <em>by design</em> and ignores the umask —
     * the JDK assumes a temp file holds something private. The rename then carries those bits onto
     * the target, so a replace did not only change a file's contents, it tightened its permissions,
     * and it did so to files that were never secrets: {@code jk-lock.toml}, whose entire purpose is
     * to be committed and read by everyone on the team; the user's own hand-written {@code jk.toml},
     * which {@code McpManifest} edits surgically; {@code jk-results.md}, which a CI runner may
     * collect as a different uid. Worse, it was sticky in the wrong direction — 0644 in, 0600 out,
     * and nothing ever widened it back, so a file loosened by hand re-tightened on the next build
     * (JK-2623).
     *
     * <p>So: create at {@code 0666 & ~umask}, which is exactly what a plain {@link Files#write}
     * would produce (the kernel masks the requested mode, so asking for {@code rw-rw-rw-} asks for
     * the default rather than for world-writable), then adopt {@code target}'s current mode when
     * {@code target} already exists. A replace preserves what it found; a create looks like any
     * other new file.
     *
     * <p>The mode is set <em>before</em> the bytes are written, not after, so a target that really is
     * {@code 0600} never has a readable window with content in it. Secrets should not be arriving
     * here at all — {@code OwnerOnlyFiles} is the path that creates {@code 0600} deliberately, and
     * every credential store in the tree uses it — but a helper this widely called does not get to
     * assume that.
     *
     * <p>Non-POSIX filesystems take neither branch and get the JDK's default: nothing to set, and
     * nothing to throw.
     */
    private static Path staging(Path parent, Path target) throws IOException {
        String prefix = "." + target.getFileName() + "-";
        if (Files.getFileAttributeView(parent, PosixFileAttributeView.class) == null) {
            return Files.createTempFile(parent, prefix, ".tmp"); // non-POSIX: no modes to manage
        }
        Path tmp;
        try {
            tmp = Files.createTempFile(parent, prefix, ".tmp", PosixFilePermissions.asFileAttribute(CREATE_MODE));
        } catch (UnsupportedOperationException noPosixAttrs) {
            return Files.createTempFile(parent, prefix, ".tmp");
        }
        Set<PosixFilePermission> existing;
        try {
            existing = Files.getPosixFilePermissions(target);
        } catch (IOException absent) {
            return tmp; // the common case: no target yet, so the umask default stands
        }
        try {
            Files.setPosixFilePermissions(tmp, existing);
        } catch (IOException ignored) {
            // Best-effort: publishing the new contents matters more than matching the old mode.
        }
        return tmp;
    }

    /**
     * What to ask for when creating a fresh staging file. The kernel applies the umask to this, so
     * it means "the default a new file gets here", not "world-writable" — under the usual
     * {@code 022} it lands as {@code rw-r--r--}, byte for byte what {@link Files#write} produces.
     */
    private static final Set<PosixFilePermission> CREATE_MODE = PosixFilePermissions.fromString("rw-rw-rw-");

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
