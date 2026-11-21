// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.OwnerOnlyFiles;
import cc.jumpkick.run.JkThreads;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.FileLockInterruptionException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * Advisory file locks for state several processes share: a ledger two engines fold into, a run
 * number both allocate from, a checkout only one build may write.
 *
 * <p>One JVM opens one channel per lock file and never a second: POSIX releases every lock a
 * process holds on a file when any descriptor to it closes, so a probe that opened and closed its
 * own channel would silently unlock the holder. Threads queue on a JVM lock in front of the
 * channel, and a re-entrant or same-process taker is answered from the JVM's own bookkeeping. A
 * filesystem that refuses the lock file or the lock degrades to the JVM lock rather than failing
 * the caller; an interrupt while waiting propagates, since a fold that ran unlocked would lose
 * rows. An interrupt inside a channel's I/O closes the channel and with it the lock, so the I/O on
 * a channel that may hold a lock runs on a thread nothing interrupts. The 0-byte lock file stays
 * on disk: unlinking it while another process holds the lock would let a third process lock a
 * fresh inode at the same path.
 *
 * <p>The locked region is one byte at the far end of the file, never the description a holder
 * writes at offset 0, so a refused process can read the description under Windows' mandatory
 * locking as well.
 */
public final class FileLocks {

    private static final ConcurrentHashMap<Path, Entry> ENTRIES = new ConcurrentHashMap<>();

    private static final long LOCK_POSITION = Long.MAX_VALUE - 1;

    /** How often {@link #describeHolder} looks for a channel while another thread holds the JVM lock. */
    private static final long DESCRIBE_POLL_MILLIS = 10;

    private FileLocks() {}

    /** The body run under a lock. */
    @FunctionalInterface
    public interface Body<T> {
        T run() throws IOException;
    }

    /** A body with nothing to return. */
    @FunctionalInterface
    public interface Action {
        void run() throws IOException;
    }

    /** Run {@code action} while holding {@code lockFile}, waiting for a holder to finish. */
    public static void withLock(Path lockFile, Action action) throws IOException {
        withLock(lockFile, () -> {
            action.run();
            return Boolean.TRUE;
        });
    }

    /** Run {@code body} while holding {@code lockFile}, waiting for a holder to finish. */
    public static <T> T withLock(Path lockFile, Body<T> body) throws IOException {
        Entry e = entry(lockFile);
        e.jvm.lock();
        try {
            if (e.holders > 0) return body.run(); // re-entered, or under this JVM's open hold
            boolean locked = e.acquire(true);
            e.holders = 1;
            try {
                return body.run();
            } finally {
                e.holders = 0;
                if (locked) e.release();
            }
        } finally {
            e.jvm.unlock();
        }
    }

    /** What {@link #tryHold} found. */
    public sealed interface Probe permits Hold, Held, Unavailable {}

    /** Another holder — a process, or this JVM — has the lock file. */
    public record Held() implements Probe {}

    /** The tree refuses a lock file, or the filesystem refuses locks; {@code reason} says which. */
    public record Unavailable(String reason) implements Probe {}

    /**
     * Take {@code lockFile} without waiting. A {@link Hold} is the caller's to close; its {@link
     * Hold#write} puts a description of the holder in the file for whoever is refused.
     */
    public static Probe tryHold(Path lockFile) {
        Entry e = entry(lockFile);
        if (!e.jvm.tryLock()) return new Held();
        try {
            if (e.holders > 0) return new Held();
            FileChannel channel;
            try {
                channel = e.open();
            } catch (IOException noLockFile) {
                return new Unavailable(noLockFile.toString());
            }
            if (channel == null) return new Unavailable("no lock file under " + lockFile.getParent());
            FileLock lock;
            try {
                lock = channel.tryLock(LOCK_POSITION, 1, false);
            } catch (IOException | RuntimeException noLock) {
                e.closeChannel();
                return new Unavailable(noLock.toString());
            }
            if (lock == null) {
                // Another process holds it; nothing of ours is using the channel.
                e.closeChannel();
                return new Held();
            }
            e.os = lock;
            e.holders = 1;
            return new Hold(e);
        } finally {
            e.jvm.unlock();
        }
    }

    /**
     * The holder's description written by {@link Hold#write}, or empty when there is none.
     *
     * <p>Never opens a second descriptor while this JVM may hold the lock: on POSIX its close would
     * drop every lock this process holds on the file. While another thread of this JVM is inside
     * the lock (a body, possibly one waiting on another process, or a hold being written), the
     * bytes are read through that thread's channel without waiting for it. The file is opened only
     * under the JVM lock with no channel of this JVM on it, so no hold can start meanwhile.
     */
    public static String describeHolder(Path lockFile) {
        if (!Files.exists(lockFile)) return "";
        Entry e = entry(lockFile);
        try {
            while (!e.jvm.tryLock(DESCRIBE_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                FileChannel busy = e.channel;
                if (busy == null) continue; // being opened or closed, or a body runs without an OS lock
                try {
                    return uninterruptibly(() -> readDescription(busy));
                } catch (ClosedChannelException released) {
                    // the hold ended under the read; ask again
                } catch (IOException unreadable) {
                    return "";
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "";
        }
        try {
            FileChannel open = e.channel;
            return open != null
                    ? uninterruptibly(() -> readDescription(open))
                    : Files.readString(lockFile, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return "";
        } finally {
            e.jvm.unlock();
        }
    }

    /**
     * Description bytes at offset 0, read positionally so a thread holding the channel is not
     * disturbed. The lock itself sits past the end of the file.
     */
    private static String readDescription(FileChannel channel) throws IOException {
        long size = channel.size();
        if (size <= 0) return "";
        ByteBuffer buf = ByteBuffer.allocate((int) Math.min(size, 64 * 1024));
        while (buf.hasRemaining() && channel.read(buf, buf.position()) > 0) {
            // until the buffer is full or the file ends
        }
        return new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8);
    }

    /**
     * Run {@code io} on a pool thread nothing interrupts and wait for it, keeping an interrupt of the
     * caller for after: an interrupt inside a {@link FileChannel}'s I/O closes the channel, and the
     * close releases every lock on it. For short calls only, since the wait cannot be interrupted.
     * Once the pool has shut down with the JVM, {@code io} runs on the caller.
     */
    private static <T> T uninterruptibly(Body<T> io) throws IOException {
        Future<T> done;
        try {
            done = JkThreads.platform().submit(io::run);
        } catch (RejectedExecutionException exiting) {
            return io.run();
        }
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return done.get();
                } catch (InterruptedException later) {
                    interrupted = true;
                } catch (ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    if (cause instanceof IOException e) throw e;
                    if (cause instanceof RuntimeException e) throw e;
                    if (cause instanceof Error e) throw e;
                    throw new IOException(cause);
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * The JVM's entry for {@code lockFile}, keyed by real path once the file exists so two
     * spellings of one inode share one channel. A spelling seen before answers from the map, with no
     * filesystem work: the lock file is never deleted, and the channel recreates it if it was.
     */
    private static Entry entry(Path lockFile) {
        Path spelling = lockFile.toAbsolutePath().normalize();
        Entry known = ENTRIES.get(spelling);
        if (known != null) return known;
        Path key = spelling;
        try {
            if (OwnerOnlyFiles.inState(key)) {
                OwnerOnlyFiles.touch(key);
            } else {
                Path parent = key.getParent();
                if (parent != null) Files.createDirectories(parent);
                try {
                    Files.createFile(key);
                } catch (FileAlreadyExistsException exists) {
                    // left by the holder before us, as intended
                }
            }
            key = key.toRealPath();
        } catch (IOException | UnsupportedOperationException noFile) {
            // keyed by spelling; open() reports the tree's refusal to the caller
        }
        Entry e = ENTRIES.computeIfAbsent(key, Entry::new);
        ENTRIES.putIfAbsent(spelling, e);
        return e;
    }

    /** One lock file as this JVM sees it: the queue in front of it, the channel, the OS lock. */
    private static final class Entry {
        final ReentrantLock jvm = new ReentrantLock();
        final Path file;

        /**
         * The open channel while a hold or a body is in progress. Written under {@link #jvm};
         * {@link #describeHolder} reads it without the lock, only to read through it.
         */
        volatile @Nullable FileChannel channel;

        @Nullable
        FileLock os;

        /** Holds and bodies in progress; the channel closes when it returns to zero. */
        int holders;

        Entry(Path file) {
            this.file = file;
        }

        @Nullable
        FileChannel open() throws IOException {
            if (channel != null) return channel;
            OpenOption[] options = {StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ};
            try {
                if (OwnerOnlyFiles.inState(file)) {
                    channel = OwnerOnlyFiles.channel(file, options);
                } else {
                    Path parent = file.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    channel = FileChannel.open(file, options);
                }
            } catch (NoSuchFileException | UnsupportedOperationException noLockFile) {
                return null;
            }
            return channel;
        }

        /**
         * Take the OS lock, waiting when {@code wait}. False when the tree or the filesystem refuses
         * it and the caller proceeds under the JVM lock alone. An interrupt while waiting, or a channel
         * found closed, closes the channel and propagates: neither is a filesystem without locks.
         */
        boolean acquire(boolean wait) throws IOException {
            FileChannel c = open();
            if (c == null) return false;
            try {
                os = wait ? c.lock(LOCK_POSITION, 1, false) : c.tryLock(LOCK_POSITION, 1, false);
            } catch (FileLockInterruptionException | ClosedChannelException lost) {
                closeChannel();
                throw lost;
            } catch (IOException noLock) {
                closeChannel(); // e.g. NFS without lockd
                return false;
            }
            return os != null;
        }

        void release() {
            try {
                if (os != null && os.isValid()) os.release();
            } catch (IOException ignored) {
                // the channel close below drops the lock regardless
            }
            os = null;
            closeChannel();
        }

        void closeChannel() {
            FileChannel c = channel;
            channel = null;
            if (c == null) return;
            try {
                c.close();
            } catch (IOException ignored) {
                // nothing to do about a descriptor that will not close
            }
        }
    }

    /** A lock taken by {@link #tryHold}; closing empties the description and releases it. */
    public static final class Hold implements Closeable, Probe {
        private final Entry entry;
        private boolean open = true;

        private Hold(Entry entry) {
            this.entry = entry;
        }

        /** Replace the lock file's content with {@code description} of this holder. */
        public void write(String description) throws IOException {
            entry.jvm.lock();
            try {
                FileChannel c = entry.channel;
                if (!open || c == null) throw new IOException("lock released: " + entry.file);
                ByteBuffer bytes = ByteBuffer.wrap(description.getBytes(StandardCharsets.UTF_8));
                uninterruptibly(() -> {
                    c.truncate(0);
                    AtomicWrites.writeFully(c, bytes, 0);
                    c.force(false);
                    return Boolean.TRUE;
                });
            } finally {
                entry.jvm.unlock();
            }
        }

        @Override
        public void close() throws IOException {
            entry.jvm.lock();
            try {
                if (!open) return;
                open = false;
                FileChannel c = entry.channel;
                if (c != null) {
                    try {
                        uninterruptibly(() -> c.truncate(0));
                    } catch (IOException ignored) {
                        // a stale description misleads nobody once the lock is gone
                    }
                }
                entry.holders = 0;
                entry.release();
            } finally {
                entry.jvm.unlock();
            }
        }
    }
}
