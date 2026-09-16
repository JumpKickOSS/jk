// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.engine.api.BuildJobFingerprint;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.ManifestNames;
import cc.jumpkick.runtime.base.ProjectIds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.ToLongFunction;
import org.jspecify.annotations.Nullable;

/**
 * Admission of plan jobs against the coordinator's own heap. Every plan job carries an estimate
 * of what it will hold in this JVM; a job is admitted when {@code max − reserve} still covers
 * what is spoken for plus its estimate, and queues otherwise — first come, first served — until
 * a running job releases its share. A queued job is not an error and holds no plan slot: it is
 * visible as {@code queued} in status and on the dashboard, and it is cancellable by jid or dir.
 *
 * <p>Spoken for is the larger of two views: the idle footprint plus the estimates of the admitted
 * jobs (so two jobs admitted in the same instant cannot both count the same free heap), and the
 * heap actually committed right now (so a job running past its estimate still holds the door).
 * An empty engine always admits: waiting on nothing would be a hang, and the running job's cap
 * is the JVM's, not this gate's.
 */
public final class MemoryAdmission {

    /** What the gate reads and does to the heap: the runtime in production, a fake in tests. */
    public interface Heap {
        long maxBytes();

        long committedBytes();

        /** A full collection, so committed heap reflects live data before a waiter is re-judged. */
        void collect();

        static Heap runtime() {
            Runtime rt = Runtime.getRuntime();
            return new Heap() {
                @Override
                public long maxBytes() {
                    return rt.maxMemory();
                }

                @Override
                public long committedBytes() {
                    return rt.totalMemory();
                }

                @Override
                public void collect() {
                    System.gc();
                }
            };
        }
    }

    /** How a wait ended. */
    public enum Verdict {
        ADMITTED,
        /** {@link #cancel} or {@link #cancelForDir} named the waiting job. */
        CANCELLED,
        /** The engine began draining while the job waited. */
        DRAINING
    }

    /** Told once, the first time a job has to wait: how many wait ahead of it. */
    @FunctionalInterface
    public interface QueuedListener {
        void queued(int ahead);
    }

    /** Headroom the gate never hands out: the coordinator's own churn between collections. */
    public static final long RESERVE_BYTES = 32L << 20;

    /** What every plan job holds regardless of inputs: graph, memos, journal rows, worker plumbing. */
    public static final long BASE_JOB_BYTES = 48L << 20;

    /**
     * Heap a standing TOML parse tree costs per byte of document. Measured on the lock and the
     * project-metrics ledger of a 78-module workspace: a 2.5 MiB ledger parses in 160 MiB and
     * fails in 128; a 100 KiB lock parses in 48 MiB and fails in 32.
     */
    public static final long TOML_TREE_BYTES_PER_BYTE = 64;

    /** How long a waiter sleeps between re-judgements when nothing wakes it. */
    static final long POLL_MS = 500;

    private final Heap heap;
    private final ToLongFunction<String> estimator;
    private final Object lock = new Object();

    /** Waiting jobs in arrival order; the head is the only one ever admitted. */
    private final LinkedHashMap<Long, Waiting> queue = new LinkedHashMap<>();

    /** Admitted jobs and the estimate each holds. */
    private final Map<Long, Long> admitted = new HashMap<>();

    private long admittedBytes;

    /** The smallest committed heap seen with no job admitted: the coordinator's idle footprint. */
    private long idleCommittedBytes;

    private static final class Waiting {
        final String dir;
        boolean cancelled;

        Waiting(String dir) {
            this.dir = dir;
        }
    }

    /** The production gate: this JVM's heap, estimates from the job's own inputs on disk. */
    public static MemoryAdmission forRuntime() {
        return new MemoryAdmission(Heap.runtime(), MemoryAdmission::estimate);
    }

    public MemoryAdmission(Heap heap, ToLongFunction<String> estimator) {
        this.heap = heap;
        this.estimator = estimator;
        this.idleCommittedBytes = heap.committedBytes();
    }

    /**
     * Admit {@code jid} for {@code dir}, waiting behind earlier jobs until its estimate fits.
     * {@code onQueued} runs once, outside the lock, if the job has to wait at all; {@code
     * draining} is polled so a drain ends the wait. An interrupt ends it as a cancel.
     */
    public Verdict admit(long jid, String dir, QueuedListener onQueued, BooleanSupplier draining) {
        long estimate = Math.max(0, estimator.applyAsLong(dir));
        int ahead;
        synchronized (lock) {
            if (queue.isEmpty() && fits(estimate)) {
                take(jid, estimate);
                return Verdict.ADMITTED;
            }
            queue.put(jid, new Waiting(dir));
            ahead = queue.size() - 1;
        }
        onQueued.queued(ahead);
        synchronized (lock) {
            try {
                while (true) {
                    Waiting self = queue.get(jid);
                    if (self == null || self.cancelled) return Verdict.CANCELLED;
                    if (draining.getAsBoolean()) return Verdict.DRAINING;
                    if (isHead(jid) && fits(estimate)) {
                        take(jid, estimate);
                        return Verdict.ADMITTED;
                    }
                    lock.wait(POLL_MS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Verdict.CANCELLED;
            } finally {
                queue.remove(jid);
                lock.notifyAll();
            }
        }
    }

    /** Give back {@code jid}'s share; idempotent. Waiters are re-judged after a collection. */
    public void release(long jid) {
        synchronized (lock) {
            Long estimate = admitted.remove(jid);
            if (estimate == null) return;
            admittedBytes -= estimate;
            if (!queue.isEmpty()) heap.collect();
            if (admitted.isEmpty()) idleCommittedBytes = Math.min(idleCommittedBytes, heap.committedBytes());
            lock.notifyAll();
        }
    }

    /** End {@code jid}'s wait as cancelled; {@code false} when it is not waiting. */
    public boolean cancel(long jid) {
        synchronized (lock) {
            Waiting w = queue.get(jid);
            if (w == null) return false;
            w.cancelled = true;
            lock.notifyAll();
            return true;
        }
    }

    /** Cancel every waiting job for {@code dir} (canonical absolute path); the count cancelled. */
    public int cancelForDir(String dir) {
        String want = canonical(dir);
        if (want == null) return 0;
        int n = 0;
        synchronized (lock) {
            for (Waiting w : queue.values()) {
                if (!w.cancelled && want.equals(canonical(w.dir))) {
                    w.cancelled = true;
                    n++;
                }
            }
            if (n > 0) lock.notifyAll();
        }
        return n;
    }

    /** Jobs waiting for memory right now. */
    public int queued() {
        synchronized (lock) {
            return queue.size();
        }
    }

    /** Jobs holding a share right now. */
    public int admittedCount() {
        synchronized (lock) {
            return admitted.size();
        }
    }

    private boolean fits(long estimate) {
        if (admitted.isEmpty()) return true;
        long spokenFor = Math.max(idleCommittedBytes + admittedBytes, heap.committedBytes());
        return estimate <= heap.maxBytes() - RESERVE_BYTES - spokenFor;
    }

    private void take(long jid, long estimate) {
        admitted.put(jid, estimate);
        admittedBytes += estimate;
    }

    private boolean isHead(long jid) {
        return !queue.isEmpty() && queue.keySet().iterator().next() == jid;
    }

    private static @Nullable String canonical(@Nullable String dir) {
        if (dir == null || dir.isBlank()) return null;
        String c = BuildJobFingerprint.canonicalDir(dir);
        if (c != null && !c.isBlank()) return c;
        try {
            return Path.of(dir).toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A plan job's coordinator cost from the TOML it parses whole: the workspace lock, the
     * project's metrics ledger and the host ledger, each standing as a parse tree at
     * {@link #TOML_TREE_BYTES_PER_BYTE}, on top of {@link #BASE_JOB_BYTES}. A job without a
     * project directory costs the base.
     */
    public static long estimate(@Nullable String dir) {
        if (dir == null || dir.isBlank()) return BASE_JOB_BYTES;
        long toml = 0;
        try {
            toml += sizeOf(Path.of(dir).resolve(ManifestNames.LOCK));
            String projectId = ProjectIds.idOf(dir);
            if (projectId != null) {
                toml += sizeOf(ProjectBuilds.projectHome(projectId).resolve(ProjectBuilds.PROJECT_METRICS));
            }
            toml += sizeOf(ProjectBuilds.hostMetricsFile());
        } catch (RuntimeException e) {
            Log.debug("estimate: inputs unreadable, base cost only", e);
        }
        return BASE_JOB_BYTES + TOML_TREE_BYTES_PER_BYTE * toml;
    }

    private static long sizeOf(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            return 0;
        }
    }
}
