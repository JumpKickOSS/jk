// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.jspecify.annotations.Nullable;

/**
 * The one lock order for destructive cache maintenance: the engine's {@code cacheGate} write lock
 * (plans hold read for their whole run), then the cross-process {@code .prune.lock} file lock.
 * Every surface that deletes under the cache tier — wire verb or MCP tool — goes through here.
 */
public final class CacheMaintenanceLocks {

    /** Maintenance work that may throw; run only while both locks are held. */
    @FunctionalInterface
    public interface Body {
        void run() throws Exception;
    }

    private CacheMaintenanceLocks() {}

    /**
     * Blocking form: waits for in-flight plans and for another process's prune. {@code
     * onWaitEngine} / {@code onWaitCross} fire once before each blocking wait (progress notices).
     * A {@code null} gate skips the engine lock (no engine in this process — tests).
     */
    public static void exclusively(
            @Nullable ReentrantReadWriteLock gate,
            Path cache,
            @Nullable Runnable onWaitEngine,
            @Nullable Runnable onWaitCross,
            Body body)
            throws Exception {
        if (gate != null && !gate.writeLock().tryLock()) {
            if (onWaitEngine != null) onWaitEngine.run();
            gate.writeLock().lock();
        }
        try {
            withPruneFileLock(cache, onWaitCross, true, body);
        } finally {
            if (gate != null) gate.writeLock().unlock();
        }
    }

    /**
     * Non-blocking form for synchronous callers (MCP tools): {@code false} when either lock is
     * busy — the caller refuses instead of deleting under an in-flight plan or a concurrent prune.
     */
    public static boolean tryExclusively(@Nullable ReentrantReadWriteLock gate, Path cache, Body body)
            throws Exception {
        if (gate != null && !gate.writeLock().tryLock()) return false;
        try {
            return withPruneFileLock(cache, null, false, body);
        } finally {
            if (gate != null) gate.writeLock().unlock();
        }
    }

    private static boolean withPruneFileLock(Path cache, @Nullable Runnable onWaitCross, boolean block, Body body)
            throws Exception {
        Files.createDirectories(cache);
        try (FileChannel chan =
                FileChannel.open(cache.resolve(".prune.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = chan.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null; // this JVM already prunes elsewhere — same as a busy cross-process lock
            }
            if (lock == null) {
                if (!block) return false;
                if (onWaitCross != null) onWaitCross.run();
                lock = chan.lock();
            }
            try {
                body.run();
            } finally {
                lock.release();
            }
        }
        return true;
    }

    /**
     * Record a completed prune so {@code usage} and the idle scheduler see fresh work. Best-effort.
     *
     * @param finalActionBytes action-tier bytes the pass left behind, or {@code -1} when it did not
     *     measure them — a wipe or a store-tier sweep. The scheduler reads that as "no pressure".
     */
    public static void stampLastPruned(Path cache, long nowMillis, long finalActionBytes) {
        cc.jumpkick.task.CachePruneScheduler.write(cache, nowMillis, finalActionBytes);
    }
}
