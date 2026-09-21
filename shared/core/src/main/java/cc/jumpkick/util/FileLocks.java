// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * Advisory file locks for state several processes share: a ledger two engines fold into, a run
 * number both allocate from, a checkout only one build may write. Threads first (one JVM lock per
 * lock file, since a second {@link FileChannel#lock()} on one file in one JVM throws), then the
 * OS lock. A filesystem that refuses the lock file or the lock degrades to the JVM lock rather
 * than failing the caller. The 0-byte lock file stays on disk: unlinking it while another process
 * holds the lock would let a third process lock a fresh inode at the same path.
 */
public final class FileLocks {

    private static final ConcurrentHashMap<Path, ReentrantLock> JVM = new ConcurrentHashMap<>();

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
        Path key = lockFile.toAbsolutePath().normalize();
        ReentrantLock jvm = JVM.computeIfAbsent(key, k -> new ReentrantLock());
        jvm.lock();
        try {
            FileChannel channel = open(key);
            if (channel == null) return body.run();
            try (channel) {
                FileLock lock;
                try {
                    lock = channel.lock();
                } catch (IOException | RuntimeException noLock) {
                    return body.run(); // e.g. NFS without lockd
                }
                try {
                    return body.run();
                } finally {
                    lock.release();
                }
            }
        } finally {
            jvm.unlock();
        }
    }

    /**
     * Take {@code lockFile} without waiting. Empty when another process (or this one) holds it;
     * the caller closes the hold to release. The hold's {@link Hold#write} puts a description of
     * the holder in the file for whoever is refused.
     */
    public static Optional<Hold> tryHold(Path lockFile) throws IOException {
        Path key = lockFile.toAbsolutePath().normalize();
        FileChannel channel = open(key);
        if (channel == null) return Optional.empty();
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException heldHere) {
            channel.close();
            return Optional.empty();
        } catch (IOException | RuntimeException noLock) {
            channel.close();
            throw noLock instanceof IOException io ? io : new IOException(noLock);
        }
        if (lock == null) {
            channel.close();
            return Optional.empty();
        }
        return Optional.of(new Hold(channel, lock));
    }

    /** The holder's description written by {@link Hold#write}, or empty when there is none. */
    public static String describeHolder(Path lockFile) {
        try {
            return Files.readString(lockFile, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return "";
        }
    }

    private static @Nullable FileChannel open(Path key) throws IOException {
        Path parent = key.getParent();
        if (parent != null) Files.createDirectories(parent);
        try {
            return FileChannel.open(key, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
        } catch (NoSuchFileException | UnsupportedOperationException noLockFile) {
            return null;
        }
    }

    /** A lock taken by {@link #tryHold}; closing releases it. */
    public static final class Hold implements Closeable {
        private final FileChannel channel;
        private final FileLock lock;

        private Hold(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        /** Replace the lock file's content with {@code description} of this holder. */
        public void write(String description) throws IOException {
            channel.truncate(0);
            channel.write(ByteBuffer.wrap(description.getBytes(StandardCharsets.UTF_8)), 0);
            channel.force(false);
        }

        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}
