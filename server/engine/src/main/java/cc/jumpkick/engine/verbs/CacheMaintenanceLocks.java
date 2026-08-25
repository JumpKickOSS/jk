// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.host.CacheTree;
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
 * Every surface that deletes under the cache tier — wire verb, MCP tool, or the engine's own
 * idle-boundary prune — goes through here.
 *
 * <p>Taking a lock is not a write, so nothing here creates the cache tree. It used to: the file
 * lock needs somewhere to put {@code .prune.lock}, and an unconditional {@code createDirectories}
 * gave it one. That put the root back after {@code jk cache nuke} — a command whose whole contract
 * is {@code rm -rf} on that path and which deliberately leaves the engine running (JK-1773) — on
 * the next pass any surface made, including passes about the artifact store that only borrow this
 * lock. An absent root has no contents to protect and no concurrent pruner to exclude, so the body
 * runs under the engine gate alone and the writers create what they need, when they need it.
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
        // No root, no lock: there is nothing under it to guard and no other pruner to shut out.
        // See the class note — creating it here is what undid `jk cache nuke`.
        if (!Files.isDirectory(cache)) {
            body.run();
            return true;
        }
        try (FileChannel chan = FileChannel.open(
                CacheTree.PRUNE_LOCK.under(cache), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
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
