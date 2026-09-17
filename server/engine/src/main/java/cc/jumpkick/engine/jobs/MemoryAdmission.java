// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.jobs;

import cc.jumpkick.builds.AggregatedMetrics;
import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.config.JobLimits;
import cc.jumpkick.engine.api.BuildHistoryKinds;
import cc.jumpkick.engine.api.BuildJobFingerprint;
import cc.jumpkick.engine.plugin.MemoryProbe;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.ManifestNames;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.DeclaredDependencies;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.runtime.base.ProjectIds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
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
 * An empty engine admits any job the heap could hold alone: waiting on nothing would be a hang.
 * A job whose estimate exceeds the whole heap less the reserve is refused at once as {@link
 * Verdict#TOO_LARGE} instead — no wait would ever admit it, and running it would end in the
 * {@code OutOfMemoryError} exit that takes every other job with it; the refusal names the cap to
 * raise.
 *
 * <p>Two fairness rules keep one long job from holding the whole budget for hours. A <em>brief</em>
 * job — any kind that is not a build, test, compile, native or image run: format, guard, lock,
 * explain, import, tree, … — is judged by the ledger of estimates alone and by the host's free
 * memory, never by the heap a long job has committed, and it does not wait its turn behind queued
 * long jobs. And the head of the queue, whatever its kind, is admitted once it has waited {@link
 * Timing#fairWaitMs} as long as the host has {@link #HOST_HEADROOM_BYTES} beyond its estimate free,
 * even when the heap view says no. A job that has waited {@link Timing#queueWaitMs} gives up as
 * {@link Verdict#TIMED_OUT}; while it waits, its listener is told its position every {@link
 * Timing#reportMs}.
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
        DRAINING,
        /** The job waited {@link Timing#queueWaitMs} without being admitted. */
        TIMED_OUT,
        /** The job's estimate exceeds what the whole heap could hold; refused without waiting. */
        TOO_LARGE
    }

    /** What a job of {@code kind} for {@code dir} will hold in this JVM, in bytes. */
    @FunctionalInterface
    public interface Estimator {
        long estimate(String kind, String dir);
    }

    /**
     * Told when a job first has to wait ({@code waitedMs == 0}) and again every {@link
     * Timing#reportMs} while it waits: how many wait ahead of it and for how long it has waited.
     * Runs outside the gate's lock.
     */
    @FunctionalInterface
    public interface QueuedListener {
        void queued(int ahead, long waitedMs);
    }

    /** What the host has left to allocate right now: {@link MemoryProbe} in production, a fake in tests. */
    @FunctionalInterface
    public interface HostMemory {
        long availableBytes();

        static HostMemory probe() {
            return () -> MemoryProbe.current().availableBytes();
        }
    }

    /**
     * The gate's clocks, in milliseconds. {@code fairWaitMs}: how long the head of the queue waits
     * before the host's free memory alone admits it. {@code queueWaitMs}: how long any job waits
     * before it gives up ({@code 0} = without bound). {@code reportMs}: how often a waiting job's
     * listener hears its position.
     */
    public record Timing(long fairWaitMs, long queueWaitMs, long reportMs) {

        public static final Timing DEFAULTS = new Timing(5 * 60_000L, JobLimits.DEFAULT_QUEUE_WAIT_MS, 60_000L);

        public Timing {
            if (fairWaitMs < 0 || queueWaitMs < 0 || reportMs <= 0) {
                throw new IllegalArgumentException(
                        "admission timings are durations in ms; the report interval is positive");
            }
        }

        public Timing withQueueWaitMs(long ms) {
            return new Timing(fairWaitMs, ms, reportMs);
        }
    }

    /** Headroom the gate never hands out: the coordinator's own churn between collections. */
    public static final long RESERVE_BYTES = 32L << 20;

    /** What every plan job holds regardless of inputs: graph, memos, journal rows, worker plumbing. */
    public static final long BASE_JOB_BYTES = 48L << 20;

    /**
     * Heap a standing TOML parse tree costs per byte of document. Measured on the workspace lock:
     * a 100 KiB lock parses in 48 MiB and fails in 32.
     */
    public static final long TOML_TREE_BYTES_PER_BYTE = 64;

    /**
     * Heap a lock holds per distinct dependency the manifests declare, for a lock with no lock on
     * disk to size it by. Across the reference reactors a lock runs 0.5–3 KiB of TOML per declared
     * dependency, which {@link #TOML_TREE_BYTES_PER_BYTE} puts at 32–200 KiB of heap each; 64 KiB
     * sizes a 1,400-dependency reactor at about 90 MiB, which is what its lock measures.
     */
    public static final long LOCK_BYTES_PER_DECLARED_DEPENDENCY = 64L << 10;

    /**
     * Heap the metrics ledgers cost per byte of file. They are scanned line by line into three
     * key-to-number maps ({@link AggregatedMetrics}), held once per session,
     * so the cost is the keys and their boxed values, not a parse tree.
     */
    public static final long LEDGER_BYTES_PER_BYTE = 8;

    /**
     * Heap an import holds per {@code pom.xml} of the reactor: the raw model of every POM stays
     * registered for parent and BOM lookups while the walk imports one module at a time, and each
     * module leaves its manifest and rows. Measured at 36 KiB per POM on a reactor of 1,900 modules
     * and doubled.
     */
    public static final long IMPORT_BYTES_PER_POM = 64L << 10;

    /** Directories an import never reads a {@code pom.xml} from: build outputs, the repository's own metadata. */
    private static final Set<String> IMPORT_SKIPPED_DIRS = Set.of(BuildLayout.TARGET, "build", ".git", "node_modules");

    /**
     * Free host memory a fairness admission leaves untouched beyond the job's own estimate: the
     * workers the job will fork and the rest of the machine.
     */
    public static final long HOST_HEADROOM_BYTES = 256L << 20;

    /** How long a waiter sleeps between re-judgements when nothing wakes it. */
    static final long POLL_MS = 500;

    private final Heap heap;
    private final Estimator estimator;
    private final HostMemory host;
    private final Timing timing;
    private final LongSupplier nowMillis;
    private final Object lock = new Object();

    /** Waiting jobs in arrival order; the head is the only one ever admitted. */
    private final LinkedHashMap<Long, Waiting> queue = new LinkedHashMap<>();

    /** Admitted jobs and the estimate each holds. */
    private final Map<Long, Long> admitted = new HashMap<>();

    private long admittedBytes;

    /** The smallest committed heap seen with no job admitted: the coordinator's idle footprint. */
    private long idleCommittedBytes;

    private static final class Waiting {
        final String kind;
        final String dir;
        final long sinceMillis;
        boolean cancelled;

        Waiting(String kind, String dir, long sinceMillis) {
            this.kind = kind;
            this.dir = dir;
            this.sinceMillis = sinceMillis;
        }
    }

    /**
     * The production gate: this JVM's heap, estimates from the job's own inputs on disk, the host's
     * live free memory, and the engine's queue wait.
     */
    public static MemoryAdmission forRuntime(long queueWaitMs, LongSupplier nowMillis) {
        return new MemoryAdmission(
                Heap.runtime(),
                MemoryAdmission::estimate,
                HostMemory.probe(),
                Timing.DEFAULTS.withQueueWaitMs(queueWaitMs),
                nowMillis);
    }

    public MemoryAdmission(Heap heap, Estimator estimator, HostMemory host, Timing timing, LongSupplier nowMillis) {
        this.heap = heap;
        this.estimator = estimator;
        this.host = host;
        this.timing = timing;
        this.nowMillis = nowMillis;
        this.idleCommittedBytes = heap.committedBytes();
    }

    /**
     * Admit {@code jid}, a job of {@code kind} for {@code dir}, waiting behind earlier jobs until
     * its estimate fits or a fairness rule admits it. {@code onQueued} runs outside the lock when
     * the job first has to wait and on every report interval after; {@code draining} is polled so
     * a drain ends the wait. An interrupt ends it as a cancel.
     */
    public Verdict admit(long jid, String kind, String dir, QueuedListener onQueued, BooleanSupplier draining) {
        long estimate = Math.max(0, estimator.estimate(kind, dir));
        if (estimate > heap.maxBytes() - RESERVE_BYTES) return Verdict.TOO_LARGE;
        boolean brief = !BuildHistoryKinds.isBuildLike(kind);
        long arrived = nowMillis.getAsLong();
        int ahead;
        synchronized (lock) {
            if ((queue.isEmpty() && fits(estimate)) || (brief && fitsBeside(estimate))) {
                take(jid, estimate);
                return Verdict.ADMITTED;
            }
            queue.put(jid, new Waiting(kind, dir, arrived));
            ahead = queue.size() - 1;
        }
        onQueued.queued(ahead, 0);
        long lastReport = arrived;
        try {
            while (true) {
                Verdict verdict;
                int position;
                long waited;
                synchronized (lock) {
                    waited = nowMillis.getAsLong() - arrived;
                    verdict = judge(jid, estimate, brief, waited, draining);
                    if (verdict != null) return verdict;
                    position = positionOf(jid);
                    if (nowMillis.getAsLong() - lastReport < timing.reportMs()) {
                        lock.wait(POLL_MS);
                        continue;
                    }
                }
                lastReport = nowMillis.getAsLong();
                onQueued.queued(position, waited);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Verdict.CANCELLED;
        } finally {
            synchronized (lock) {
                queue.remove(jid);
                lock.notifyAll();
            }
        }
    }

    /**
     * One re-judgement of a waiting job, under the lock: the verdict that ends its wait, or {@code
     * null} to keep waiting. Admission takes the share before returning.
     */
    private @Nullable Verdict judge(long jid, long estimate, boolean brief, long waited, BooleanSupplier draining) {
        Waiting self = queue.get(jid);
        if (self == null || self.cancelled) return Verdict.CANCELLED;
        if (draining.getAsBoolean()) return Verdict.DRAINING;
        if (timing.queueWaitMs() > 0 && waited >= timing.queueWaitMs()) return Verdict.TIMED_OUT;
        boolean head = isHead(jid);
        if ((head && fits(estimate))
                || (brief && fitsBeside(estimate))
                || (head && waited >= timing.fairWaitMs() && hostHasRoom(estimate))) {
            take(jid, estimate);
            return Verdict.ADMITTED;
        }
        return null;
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

    /** The gate's clocks: the fair wait, the give-up bound and the report interval. */
    public Timing timing() {
        return timing;
    }

    /** What a job of {@code kind} for {@code dir} is estimated to hold, in bytes — the figure a refusal names. */
    public long estimateFor(String kind, String dir) {
        return Math.max(0, estimator.estimate(kind, dir));
    }

    /** The heap's ceiling in bytes, as the gate sees it. */
    public long heapMaxBytes() {
        return heap.maxBytes();
    }

    /** Jobs waiting for memory right now. */
    public int queued() {
        synchronized (lock) {
            return queue.size();
        }
    }

    /** Every waiting job as a status row, in arrival order, each with how many wait ahead of it. */
    public List<JobRow> queuedRows() {
        List<JobRow> out = new ArrayList<>();
        synchronized (lock) {
            int ahead = 0;
            for (Map.Entry<Long, Waiting> e : queue.entrySet()) {
                Waiting w = e.getValue();
                out.add(JobRow.queued(e.getKey(), w.kind, w.dir, w.sinceMillis, ahead++));
            }
        }
        return out;
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

    /**
     * The brief-job view: the ledger of estimates says the heap holds it beside the admitted jobs,
     * and the host has room for it. The committed heap is not consulted — a long job's committed
     * heap is mostly garbage that a collection returns, and waiting for one is what starved the
     * queue.
     */
    private boolean fitsBeside(long estimate) {
        if (admitted.isEmpty()) return true;
        return estimate <= heap.maxBytes() - RESERVE_BYTES - (idleCommittedBytes + admittedBytes)
                && hostHasRoom(estimate);
    }

    private boolean hostHasRoom(long estimate) {
        return host.availableBytes() >= estimate + HOST_HEADROOM_BYTES;
    }

    private int positionOf(long jid) {
        int i = 0;
        for (Long waiting : queue.keySet()) {
            if (waiting == jid) return i;
            i++;
        }
        return i;
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
     * A plan job's coordinator cost from what it reads whole. An import holds the reactor: {@link
     * #IMPORT_BYTES_PER_POM} per {@code pom.xml} under {@code dir}, build outputs pruned. Any other
     * plan holds the workspace lock as a parse tree at {@link #TOML_TREE_BYTES_PER_BYTE}, the
     * project's metrics ledger and the host ledger as scanned maps at {@link
     * #LEDGER_BYTES_PER_BYTE}. A lock holds the graph it solves, which the lock on disk sizes when
     * there is one and the manifests' declared dependencies size at {@link
     * #LOCK_BYTES_PER_DECLARED_DEPENDENCY} each when there is not — the larger of the two. All sit
     * on top of {@link #BASE_JOB_BYTES}, which is all a job without a project directory costs.
     */
    public static long estimate(String kind, @Nullable String dir) {
        if (dir == null || dir.isBlank()) return BASE_JOB_BYTES;
        if ("import".equals(kind)) return BASE_JOB_BYTES + IMPORT_BYTES_PER_POM * pomCount(Path.of(dir));
        long toml = 0;
        long declared = 0;
        long ledgers = 0;
        try {
            toml += sizeOf(Path.of(dir).resolve(ManifestNames.LOCK));
            if ("lock".equals(kind))
                declared = DeclaredDependencies.countDistinct(LockPaths.lockOwnerDir(Path.of(dir)));
            String projectId = ProjectIds.idOf(dir);
            if (projectId != null) {
                ledgers += sizeOf(ProjectBuilds.projectHome(projectId).resolve(ProjectBuilds.PROJECT_METRICS));
            }
            ledgers += sizeOf(ProjectBuilds.hostMetricsFile());
        } catch (RuntimeException e) {
            Log.debug("estimate: inputs unreadable, base cost only", e);
        }
        long graph = Math.max(TOML_TREE_BYTES_PER_BYTE * toml, LOCK_BYTES_PER_DECLARED_DEPENDENCY * declared);
        return BASE_JOB_BYTES + graph + LEDGER_BYTES_PER_BYTE * ledgers;
    }

    /** Every {@code pom.xml} under {@code root} outside {@link #IMPORT_SKIPPED_DIRS}; zero when the tree cannot be read. */
    static long pomCount(Path root) {
        AtomicLong poms = new AtomicLong();
        try {
            PathUtil.forEachRegularFile(
                    root, dir -> IMPORT_SKIPPED_DIRS.contains(String.valueOf(dir.getFileName())), (file, attrs) -> {
                        if ("pom.xml".equals(String.valueOf(file.getFileName()))) poms.incrementAndGet();
                    });
        } catch (IOException | RuntimeException e) {
            Log.debug("estimate: reactor unreadable, counted " + poms.get() + " POMs", e);
        }
        return poms.get();
    }

    private static long sizeOf(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            return 0;
        }
    }
}
