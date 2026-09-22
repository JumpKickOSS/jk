// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import cc.jumpkick.host.Os;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
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
 * <p><strong>What it does not guarantee:</strong> crash consistency. There is no {@code fsync}
 * before the rename — and none anywhere in the product; the only
 * {@link java.nio.channels.FileChannel#force} in the tree is a disk benchmark. On ext4
 * {@code data=ordered} or APFS, a power loss can make the rename durable while the data blocks are
 * not, leaving a zero-length or truncated target.
 *
 * <p>Use {@link #replaceDurably} for a file where a torn target is not recoverable by re-running.
 * There are three: {@code jk-lock.toml}, the engine install pointer, and
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
     * Attempts before a denied replace is final.
     *
     * <p>Windows denies a REPLACE while any handle has the target open, and {@code
     * FILE_SHARE_DELETE} does not help: the JDK opens both its read path and its
     * set-last-modified path with that flag, and the replace is denied all the same. Measured on
     * NTFS against one thread reading the target in a loop, <b>78% of replace attempts are
     * denied</b>.
     *
     * <p>What clears it is a gap between the reader's opens, so the number that matters is how
     * many times this looks rather than how long it waits. Same measurement, eight writers against
     * two looping readers, share of stores that ran out of attempts:
     *
     * <pre>
     *    8 attempts, 20 ms apart  (160 ms)   11.17%
     *    8 attempts, 60 ms apart  (480 ms)   12.57%   — three times the wait, no better
     *   16 attempts, 20 ms apart  (320 ms)    0.37%
     *   32 attempts, 20 ms apart  (640 ms)    0.00%
     * </pre>
     *
     * So: poll often, and enough times. The wait is capped rather than growing without bound for
     * the same reason — a long sleep spends the window it is waiting for. The common case pays
     * nothing (an uncontended replace never retries) and a contended one averaged 67 ms.
     */
    private static final int MOVE_ATTEMPTS = 32;

    /** Ceiling on the pause between attempts; see {@link #MOVE_ATTEMPTS} for why it is not unbounded. */
    private static final long MOVE_BACKOFF_CAP_MILLIS = 20;

    private AtomicWrites() {}

    /**
     * Write {@code bytes} to {@code target} atomically.
     *
     * <p>The cleanup is on the failure path, not in a {@code finally}: a successful
     * {@link #moveInto} has already consumed {@code tmp}, so a {@code finally} unlink was an
     * unlink of a path that could not exist — one wasted metadata call on every success, at
     * fifty call sites and once per CAS blob. On NTFS that op is ~11&nbsp;µs against
     * Linux's ~1.5.
     */
    public static void replace(Path target, byte[] bytes) throws IOException {
        Path parent = directoryOf(target);
        Files.createDirectories(parent);
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
     * the engine install pointer, {@code run-number}. Everything else in this tree
     * is a cache or a hint whose loss costs one recomputation, and paying an {@code fsync} for those
     * would cost more than it protects — see the class javadoc.
     */
    public static void replaceDurably(Path target, byte[] bytes) throws IOException {
        Path parent = directoryOf(target);
        Files.createDirectories(parent);
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
     *.
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
    /**
     * The directory {@code target} lives in: its parent, or for a bare file name such as
     * {@code jk-lock.toml} the working directory, which is where the staging sibling must land for
     * the rename to stay on one filesystem.
     */
    private static Path directoryOf(Path target) {
        Path parent = target.getParent();
        if (parent != null) return parent;
        return Objects.requireNonNull(target.toAbsolutePath().getParent(), "a file has a directory");
    }

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
     * <p>On Windows only, a denied replace is retried — see {@link #MOVE_ATTEMPTS} for the budget
     * and the measurement behind it: another handle on the target makes the rename fail until it
     * closes, whatever sharing that handle was opened with. A POSIX denial throws on the first
     * attempt.
     * Do not rename the target aside to dodge a lock — that leaves a name gap concurrent readers
     * observe as {@code NoSuchFileException}, which breaks the atomicity this class promises.
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
            } catch (IOException e) {
                // POSIX EACCES is permanent. On Windows, ERROR_ACCESS_DENIED is AccessDeniedException
                // and ERROR_SHARING_VIOLATION is a bare FileSystemException (OpenJDK's default
                // branch) — either can mean another handle still has the target open.
                if (!Os.isWindows() || !isTransientWindowsLock(e)) throw e;
                if (attempt == MOVE_ATTEMPTS) throw e;
                backOff.accept(attempt);
            }
        }
    }

    /**
     * Read {@code target} as UTF-8, retrying a Windows denial the way {@link #moveInto} does.
     *
     * <p>The mirror of the replace race, and the reason it needs the same treatment: Windows
     * denies a replace while a reader holds the target, and it denies a reader that opens the
     * target while a replace is landing on it. A caller that reads a name {@link #replace} writes
     * therefore has to expect a denial it did nothing to cause. Same attempt budget and the same
     * back-off — see {@link #MOVE_ATTEMPTS} for the measurement.
     *
     * <p>A POSIX denial throws on the first attempt, as it does for the move: {@code EACCES} is a
     * permissions problem that waiting cannot clear. {@link java.nio.file.NoSuchFileException} is
     * not retried either — a name that is gone is gone, and what that means is the caller's to
     * decide.
     */
    public static String readString(Path target) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                return Files.readString(target);
            } catch (IOException e) {
                if (!Os.isWindows() || !isTransientWindowsLock(e)) throw e;
                if (attempt == MOVE_ATTEMPTS) throw e;
                backOff.accept(attempt);
            }
        }
    }

    /**
     * Windows denials that clear when a handle closes. {@link AccessDeniedException} is
     * {@code ERROR_ACCESS_DENIED}; a bare {@link FileSystemException} is
     * {@code ERROR_SHARING_VIOLATION}. Typed subclasses ({@link java.nio.file.NoSuchFileException},
     * …) are permanent and must not retry.
     */
    public static boolean isTransientWindowsLock(IOException e) {
        return e instanceof AccessDeniedException || e.getClass() == FileSystemException.class;
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
     * property; milliseconds were a proxy for it. Package-private, non-final, and never
     * reassigned outside a test.
     */
    static IntConsumer backOff = AtomicWrites::sleepBriefly;

    private static void sleepBriefly(int attempt) {
        try {
            // Short and then flat: the first attempts are cheap for a replace that is merely
            // unlucky, and the rest poll at a steady rate for one that is up against a reader.
            Thread.sleep(Math.min(5L * attempt, MOVE_BACKOFF_CAP_MILLIS));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
