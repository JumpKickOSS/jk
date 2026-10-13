// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.Semaphore;

/**
 * Heap the guard lanes in flight may hold as decoded facts indexes, across every job of the engine.
 * A lane takes its share before it decodes and gives it back when it ends; a lane whose share is the
 * whole budget runs alone.
 */
final class GuardFactsBudget {

    /** Heap a decoded index holds per byte of its file: a 3.3 MB index decodes to 14.6 MiB. */
    static final long DECODED_BYTES_PER_FILE_BYTE = 5;

    private static final long KIB = 1024;

    private final int totalKib;
    private final Semaphore kib;

    GuardFactsBudget(long bytes) {
        totalKib = (int) Math.clamp(bytes / KIB, 1, Integer.MAX_VALUE);
        kib = new Semaphore(totalKib, true);
    }

    /** An eighth of this JVM's heap ceiling. */
    static GuardFactsBudget forRuntime() {
        return new GuardFactsBudget(Runtime.getRuntime().maxMemory() / 8);
    }

    /** A share for decoding the indexes in {@code files}; blocks until the budget has room for it. */
    Held hold(Collection<Path> files) {
        int share = shareKib(fileBytes(files));
        if (share == 0) return () -> {};
        kib.acquireUninterruptibly(share);
        return () -> kib.release(share);
    }

    /** The share for indexes {@code fileBytes} long on disk, clamped to the whole budget. */
    int shareKib(long fileBytes) {
        long decoded = fileBytes * DECODED_BYTES_PER_FILE_BYTE;
        return (int) Math.min(totalKib, (decoded + KIB - 1) / KIB);
    }

    /** KiB no lane holds right now. Test seam. */
    int availableKib() {
        return kib.availablePermits();
    }

    private static long fileBytes(Collection<Path> files) {
        long bytes = 0;
        for (Path f : files) {
            try {
                if (Files.isRegularFile(f)) bytes += Files.size(f);
            } catch (IOException gone) {
                // An index that vanished is decoded by no one.
            }
        }
        return bytes;
    }

    /** A lane's share, given back on close. */
    interface Held extends AutoCloseable {
        @Override
        void close();
    }
}
