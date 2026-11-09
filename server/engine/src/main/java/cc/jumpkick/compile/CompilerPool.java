// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.engine.plugin.HostPressure;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.engine.plugin.WarmLinger;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * One resident compiler worker per key, the queue of compiles waiting for it, and its lifetime.
 *
 * <p><strong>One worker, many compiles.</strong> A key is everything a running JVM cannot be given:
 * its JDK, its worker jar, the environment it was started with, the module's own {@code -J} flags
 * and, for a mixed Scala compile, the compiler classpath. A plain Java build therefore has exactly one
 * worker per JDK, and it runs as many compiles at once as it has threads. The queue lives here in
 * the engine, never in the worker: an item goes to the worker only when {@link #mayAdmit} says the
 * worker may run another, so the worker is never handed work it would have to sit on.
 *
 * <p><strong>Lifetime.</strong> A pool outlives the jobs that use it, for the {@link
 * JavaCompilerHost#lingerMillis linger} after its last item: the next build compiles on a JVM
 * whose JIT, jar indexes and processor loaders are warm. A linger of {@code 0}, and every mixed
 * Scala compile, makes the pool its job's, retired when the job ends. On CI the most recently used
 * pool outlives the linger ({@link WarmLinger#keepsLatest}).
 *
 * <p><strong>Death.</strong> When a worker dies, every item it was running goes back on the queue,
 * ahead of what was waiting, and a fresh worker takes them one at a time until each of them has
 * answered: the compile that killed the worker then dies alone, and only it is charged a second
 * death, which fails it with what the worker printed. A worker that ran out of heap halves its
 * pool's concurrency for {@link #OUT_OF_HEAP_HALVING}. A worker that dies before it ever said hello
 * cannot be started, so everything queued fails with its death rather than starting worker after
 * worker.
 *
 * <p><strong>A job's end.</strong> Its queued compiles fail and those in flight are cancelled. The
 * job's teardown waits for their answers, and a worker whose compile for the job does not answer
 * within {@link #END_WAIT} is stopped, so no compile of an ended job goes on writing classes under
 * the next build's; the other jobs' compiles on that worker run again, uncharged.
 */
final class CompilerPool {

    /** Every pool by key. A job-scoped key starts with its job's id and a {@code |}. */
    private static final ConcurrentHashMap<String, CompilerPool> POOLS = new ConcurrentHashMap<>();

    /** Every pool not yet closed, registered or not: a credit coming back anywhere wakes them all. */
    private static final Set<CompilerPool> LIVE = ConcurrentHashMap.newKeySet();

    /** One {@link JobWorkers} scope per resident worker, counting up from below every job and ephemeral id. */
    private static final AtomicLong SCOPES = new AtomicLong(Long.MIN_VALUE);

    private static final Clock CLOCK = Clock.SYSTEM;

    /** How long a job's end waits for its compiles to answer their cancel before stopping their worker. */
    static final Duration END_WAIT = Duration.ofSeconds(10);

    /** How long a worker's death by running out of heap halves its pool's concurrency. */
    static final Duration OUT_OF_HEAP_HALVING = Duration.ofMinutes(5);

    /** How often a caller waiting on a compile checks that something still holds it. */
    private static final long LIVENESS_EVERY_MS = 30_000;

    /** How long {@link #close} waits for the workers to finish and exit before stopping them. */
    private static volatile long closeWaitMillis = TimeUnit.SECONDS.toMillis(15);

    private static volatile boolean hookRegistered;

    /** The resident pool most recently given an item; under CI it outlives the linger. */
    private static volatile @Nullable CompilerPool latest;

    /** Starts a worker for a pool. Production forks the worker JVM for the pool's key. */
    @FunctionalInterface
    interface Starter {
        CompilerWorker.Launcher launcher();
    }

    /** Writes the spec file a dispatch hands the worker. Production is {@link ForkedJavac#writeSpec}. */
    @FunctionalInterface
    interface SpecFile {
        Path write(ForkedJavac.Request req) throws IOException;
    }

    final String key;
    final SpecFile specs;
    private final Starter starter;

    /** The job a job-scoped pool belongs to, whose scope its worker runs under; null for a resident pool. */
    private final @Nullable Long job;

    /** Guarded by {@code this}. */
    private final ArrayDeque<CompileWork> queue = new ArrayDeque<>();

    /** Guarded by {@code this}: normally one, briefly two while a dead worker's replacement starts. */
    private final List<CompilerWorker> workers = new ArrayList<>();

    /** Items submitted and not yet answered, retries counted once. Guarded by {@code this}. */
    private int items;

    /** Items a death put back that have not answered yet; while any has not, items go one at a time. */
    private int revivedPending;

    /** Until when a death by running out of heap halves concurrency, in {@link #CLOCK} nanos. */
    private long halvedUntilNanos = CLOCK.nanos();

    private boolean closed;
    private volatile long lastUsedNanos = CLOCK.nanos();
    private @Nullable Thread reaper;

    CompilerPool(String key, @Nullable Long job, Starter starter, SpecFile specs) {
        this.key = key;
        this.job = job;
        this.starter = starter;
        this.specs = specs;
        LIVE.add(this);
    }

    /**
     * The pool for {@code key}, made by {@code make} when there is none, with {@code item} counted
     * against it until the item is answered: a pool with an item on it is never retired, so an item
     * cannot land on a pool its reaper has just closed. A job-scoped pool is never made for a job
     * that has ended; nothing would ever retire it.
     */
    static CompilerPool forKey(String key, @Nullable Long job, Supplier<CompilerPool> make, CompileWork item) {
        if (job != null && JobWorkers.ended(job)) {
            throw new UncheckedIOException(new InterruptedIOException("job " + job + " has ended"));
        }
        CompilerPool[] held = new CompilerPool[1];
        POOLS.compute(key, (k, existing) -> {
            CompilerPool pool = existing != null ? existing : make.get();
            synchronized (pool) {
                pool.items++;
            }
            held[0] = pool;
            return pool;
        });
        CompilerPool pool = held[0];
        item.compile.whenComplete((r, e) -> pool.settled());
        if (pool.job == null) {
            latest = pool;
            pool.armReaper();
        }
        registerHooks();
        return pool;
    }

    /** The pool for {@code key} when it has a worker that has said hello, else null. */
    static @Nullable CompilerPool warm(String key) {
        CompilerPool pool = POOLS.get(key);
        return pool != null && pool.hasReadyWorker() ? pool : null;
    }

    /** A fresh scope for one resident worker. */
    static long nextScope() {
        return SCOPES.getAndIncrement();
    }

    /** The scope a worker of this pool runs under: its job's, or one of its own. */
    long workerScope() {
        Long j = job;
        return j != null ? j : nextScope();
    }

    /** The job a job-scoped pool belongs to, or null for a resident pool. */
    @Nullable
    Long job() {
        return job;
    }

    /**
     * {@code job} has ended: its queued items fail, its items in flight are cancelled on their
     * workers — every other job's compiles go on — and its job-scoped pools are retired. Returns once
     * every compile of the job has answered, or its worker has been stopped and is gone.
     */
    static void endJob(long job) {
        String prefix = job + "|";
        for (var e : List.copyOf(POOLS.entrySet())) {
            if (e.getKey().startsWith(prefix) && POOLS.remove(e.getKey(), e.getValue()))
                e.getValue().close();
        }
        Predicate<CompileWork> mine = w -> w.job == job;
        for (CompilerPool pool : List.copyOf(LIVE)) pool.abandon(mine);
        awaitOrStop(job, END_WAIT);
    }

    /**
     * Wait up to {@code wait} for {@code job}'s compiles in flight to answer; stop every worker still
     * running one after that and wait for it to be gone.
     */
    static void awaitOrStop(long job, Duration wait) {
        List<CompileWork> mine = new ArrayList<>();
        for (CompilerPool pool : List.copyOf(LIVE)) mine.addAll(pool.inflightOf(job));
        if (mine.isEmpty()) return;
        CompletableFuture<?>[] answers =
                mine.stream().map(w -> w.compile.handle((r, e) -> null)).toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(answers).get(wait.toMillis(), TimeUnit.MILLISECONDS);
            return;
        } catch (TimeoutException e) {
            // Below: stop the workers whose compiles would not answer.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            return;
        }
        List<CompilerWorker> stopped = new ArrayList<>();
        for (CompilerPool pool : List.copyOf(LIVE)) {
            for (CompilerWorker worker : pool.workers()) {
                if (worker.inflight.values().stream().anyMatch(w -> w.job == job && !w.done())) {
                    Log.info("jk engine: stopping the compiler worker " + worker.pid()
                            + ": a compile of an ended job did not answer its cancel in " + wait.toSeconds() + " s");
                    worker.killFor(job);
                    stopped.add(worker);
                }
            }
        }
        for (CompilerWorker worker : stopped) worker.join(closeWaitMillis);
    }

    /** Retire every resident pool with no work that has been idle at least {@code minIdle}; how many. */
    static int shedIdle(Duration minIdle) {
        int shed = 0;
        long now = CLOCK.nanos();
        for (CompilerPool pool : List.copyOf(POOLS.values())) {
            if (pool.job != null || now - pool.lastUsedNanos < minIdle.toNanos()) continue;
            if (pool.retireIfIdle()) shed++;
        }
        return shed;
    }

    /** Test seam: retire every pool now and wait for its workers to exit. */
    static void closeAllForTests() {
        for (String k : List.copyOf(POOLS.keySet())) {
            CompilerPool pool = POOLS.remove(k);
            if (pool != null) pool.close();
        }
    }

    /** Test seam: how long {@link #close} waits before it stops a worker. */
    static void closeWaitForTests(long millis) {
        closeWaitMillis = millis;
    }

    /** Test seam: pools alive now. */
    static int poolCount() {
        return POOLS.size();
    }

    /** Test seam: the pool for {@code key}, or null. */
    static @Nullable CompilerPool get(String key) {
        return POOLS.get(key);
    }

    /**
     * Queue {@code w} and make sure a worker exists to take it. Items wait here until a worker may
     * run another; a closed pool, or an item whose job has ended, fails at once.
     */
    void enqueue(CompileWork w) {
        w.enqueuedNanos = CLOCK.nanos();
        if (JobWorkers.ended(w.job)) {
            w.fail(new InterruptedIOException("compile of an ended job"));
            return;
        }
        synchronized (this) {
            if (!closed) {
                queue.addLast(w);
                ensureWorker();
                notifyAll();
                return;
            }
        }
        w.fail(new IOException("zinc worker pool closed"));
    }

    /** Under the lock: start a worker unless a live one, not retiring, is already here. */
    private void ensureWorker() {
        workers.removeIf(CompilerWorker::dead);
        for (CompilerWorker w : workers) {
            if (!w.retiring()) return;
        }
        CompilerWorker worker = new CompilerWorker(this, workerScope(), starter.launcher());
        workers.add(worker);
        worker.start();
    }

    /**
     * Whether {@code worker} may be sent another item now. This is the one place that decides it:
     * a free thread on the worker (half of them for a while after a death by running out of heap,
     * one while compiles a death put back have not answered), and a free compile slot engine-wide
     * ({@link CompilerBudget}), room for {@code next} in the worker's heap ({@link #heapAdmits}), and
     * a host that is not short of memory ({@link CompilerBudget#hostAdmits}).
     */
    synchronized boolean mayAdmit(CompilerWorker worker, CompileWork next) {
        if (!worker.ready()) return false;
        int credits = worker.threads();
        if (CLOCK.nanos() - halvedUntilNanos < 0) credits = Math.max(1, credits / 2);
        if (revivedPending > 0) credits = 1;
        return worker.inflight.size() < credits
                && CompilerBudget.slotFree()
                && heapAdmits(worker, next)
                && CompilerBudget.hostAdmits(worker.inflight.size());
    }

    /** The share of a worker's heap ceiling the compiles in flight may be estimated to hold together. */
    static final double HEAP_ADMIT_FRACTION = 0.7;

    /** GC time over this share of the worker's last interval holds new compiles back. */
    static final double GC_PRESSURE_FRACTION = 0.10;

    /**
     * Whether {@code worker}'s heap has room for {@code next}. A worker with nothing in flight always
     * takes one, however large, so a compile bigger than any budget still runs, alone. Otherwise the
     * in-flight compiles' estimated demand plus {@code next}'s must fit in {@link
     * #HEAP_ADMIT_FRACTION} of the ceiling the worker reports, and the worker must not be under
     * pressure: a heap left above that fraction after its last collection, or collections taking
     * more than {@link #GC_PRESSURE_FRACTION} of its time, admits nothing more until it eases.
     * Before the first heap report there is nothing to measure against, and credits alone decide.
     */
    static boolean heapAdmits(CompilerWorker worker, CompileWork next) {
        long inflightDemand = 0;
        for (CompileWork w : worker.inflight.values()) inflightDemand += w.demandBytes;
        return heapAdmits(worker.inflight.size(), inflightDemand, next.demandBytes, worker.heap());
    }

    /** {@link #heapAdmits(CompilerWorker, CompileWork)} over its readings. */
    static boolean heapAdmits(
            int inflight, long inflightDemand, long nextDemand, CompilerWorker.@Nullable HeapReading heap) {
        if (inflight == 0) return true;
        if (heap == null || heap.max() <= 0) return true;
        long room = (long) (heap.max() * HEAP_ADMIT_FRACTION);
        if (heap.usedAfterGc() > room || heap.gcFrac() > GC_PRESSURE_FRACTION) return false;
        return inflightDemand + nextDemand <= room;
    }

    /** Something a dispatcher waits on changed: a worker said hello, or a credit came back. */
    synchronized void wake() {
        notifyAll();
    }

    /** A credit came back somewhere: every pool's dispatchers look again. */
    private static void wakeAll() {
        for (CompilerPool pool : LIVE) pool.wake();
    }

    /** Whether nothing is queued. */
    synchronized boolean idle() {
        return queue.isEmpty();
    }

    /**
     * Block until {@code worker} may take the next queued item and take it into flight, or return
     * null once the worker is dead or retiring or the pool is closed. Items of jobs that ended while
     * they waited fail here instead of starting. The item counts against the worker from here.
     */
    synchronized @Nullable CompileWork nextFor(CompilerWorker worker) throws InterruptedException {
        while (true) {
            if (worker.dead() || worker.retiring() || closed) return null;
            dropEnded();
            if (!queue.isEmpty() && mayAdmit(worker, queue.peekFirst())) {
                CompileWork next = queue.pollFirst();
                worker.inflight.put(next.id, next);
                CompilerBudget.took();
                worker.busyNow();
                worker.noteInflight();
                return next;
            }
            wait(1_000);
        }
    }

    /** Under the lock: fail every queued item whose job has ended. */
    private void dropEnded() {
        for (Iterator<CompileWork> it = queue.iterator(); it.hasNext(); ) {
            CompileWork w = it.next();
            if (JobWorkers.ended(w.job)) {
                it.remove();
                w.fail(new InterruptedIOException("compile of an ended job"));
            }
        }
    }

    /** Under the lock: give {@code w} its spec while it is still in flight on {@code worker}. */
    synchronized boolean attachSpec(CompilerWorker worker, CompileWork w, Path spec) {
        if (worker.inflight.get(w.id) != w) return false;
        w.spec = spec;
        return true;
    }

    /**
     * Take item {@code id} out of flight on {@code worker}: whoever gets it back answers it. A worker
     * left with nothing in flight and nothing queued is told to trim once it has stayed idle. Null
     * when the item already left, by a death or another frame.
     */
    @Nullable
    CompileWork settle(CompilerWorker worker, long id) {
        CompileWork w;
        boolean idle;
        synchronized (this) {
            w = worker.inflight.remove(id);
            if (w != null) CompilerBudget.gave(1);
            idle = w != null && worker.inflight.isEmpty() && queue.isEmpty() && !worker.dead();
            notifyAll();
        }
        if (w != null) wakeAll();
        if (idle) worker.idleNow();
        return w;
    }

    /**
     * {@code worker} is gone. Each item it held goes back on the queue, ahead of what was waiting,
     * when its job is still alive and this is its first death; a second death, an ended job or a
     * closed pool fails it with the worker's transcript. A worker stopped for another job's compile
     * charges its other items nothing. A worker that never said hello fails the queue too.
     */
    void workerDied(CompilerWorker worker, Throwable cause) {
        List<CompileWork> failed = new ArrayList<>();
        List<CompileWork> firstDeaths = new ArrayList<>();
        boolean started = worker.threads() > 0;
        Long stoppedFor = worker.stoppedForJob();
        CompilerBudget.release(worker);
        synchronized (this) {
            workers.remove(worker);
            List<CompileWork> held = worker.takeInflight();
            CompilerBudget.gave(held.size());
            for (int i = held.size() - 1; i >= 0; i--) {
                CompileWork w = held.get(i);
                CompilerWorker.deleteSpec(w);
                boolean charged = stoppedFor == null;
                if (charged) w.deaths++;
                boolean again = !closed
                        && !w.done()
                        && !JobWorkers.ended(w.job)
                        && (stoppedFor == null || w.job != stoppedFor)
                        && w.deaths < 2;
                if (!again) {
                    failed.add(w);
                    continue;
                }
                w.clearAttempt();
                queue.addFirst(w);
                if (charged && w.deaths == 1) firstDeaths.add(w);
            }
            revivedPending += firstDeaths.size();
            if (worker.outOfHeap()) halvedUntilNanos = CLOCK.nanos() + OUT_OF_HEAP_HALVING.toNanos();
            if (!started || closed) {
                failed.addAll(queue);
                queue.clear();
            } else if (!queue.isEmpty()) {
                ensureWorker();
            }
            notifyAll();
        }
        for (CompileWork w : firstDeaths) w.compile.whenComplete((r, e) -> revivedAnswered());
        wakeAll();
        Throwable report = withTranscript(worker, cause);
        for (CompileWork w : failed) w.fail(report);
    }

    /** A compile a death put back has answered; once all have, items go out at full concurrency again. */
    private synchronized void revivedAnswered() {
        revivedPending = Math.max(0, revivedPending - 1);
        notifyAll();
    }

    /** Attach what the worker printed to a death, so a failure says why rather than only that. */
    static Throwable withTranscript(CompilerWorker worker, Throwable cause) {
        if (worker.transcript.isEmpty()) return cause;
        return new IOException(
                cause.getMessage() + "\n--- zinc worker output ---\n" + worker.transcript.render(), cause);
    }

    /**
     * Fail every queued item {@code which} picks and cancel every one in flight: a cancelled compile
     * answers {@code cancelled}, and its worker goes on with the others.
     */
    void abandon(Predicate<CompileWork> which) {
        List<CompileWork> dropped = new ArrayList<>();
        List<Runnable> cancels = new ArrayList<>();
        synchronized (this) {
            for (Iterator<CompileWork> it = queue.iterator(); it.hasNext(); ) {
                CompileWork w = it.next();
                if (which.test(w)) {
                    it.remove();
                    dropped.add(w);
                }
            }
            for (CompilerWorker worker : workers) {
                for (CompileWork w : worker.inflight.values()) {
                    if (which.test(w)) cancels.add(() -> worker.cancel(w.id));
                }
            }
        }
        for (CompileWork w : dropped) w.fail(new InterruptedIOException("compile cancelled while queued"));
        for (Runnable cancel : cancels) cancel.run();
    }

    /** {@code job}'s items in flight on this pool's workers. */
    synchronized List<CompileWork> inflightOf(long job) {
        List<CompileWork> mine = new ArrayList<>();
        for (CompilerWorker worker : workers) {
            for (CompileWork w : worker.inflight.values()) {
                if (w.job == job) mine.add(w);
            }
        }
        return mine;
    }

    /** Whether {@code w} is queued here or in flight on one of this pool's live workers. */
    synchronized boolean holds(CompileWork w) {
        if (queue.contains(w)) return true;
        for (CompilerWorker worker : workers) {
            if (!worker.dead() && worker.inflight.get(w.id) == w) return true;
        }
        return false;
    }

    /** The caller of {@code w} has gone: take it off the queue, or cancel it on its worker. */
    void withdraw(CompileWork w) {
        abandon(other -> other == w);
        w.fail(new InterruptedIOException("compile interrupted"));
    }

    /** Wait for {@code w}'s compile, withdrawing it when this thread is interrupted. */
    ForkedJavac.Result submit(CompileWork w) {
        enqueue(w);
        try {
            awaitAnswer(w);
            return w.compile.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            withdraw(w);
            throw new UncheckedIOException(new InterruptedIOException("compile interrupted while queued"));
        } catch (Exception e) {
            throw JavaCompilerHost.unwrap(e);
        }
    }

    /** As {@link #submit} for a plan. */
    ForkedJavac.Plan submitPlan(CompileWork w) {
        enqueue(w);
        try {
            awaitAnswer(w);
            return w.forecast.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            withdraw(w);
            throw new UncheckedIOException(new InterruptedIOException("plan interrupted while queued"));
        } catch (Exception e) {
            throw JavaCompilerHost.unwrap(e);
        }
    }

    /**
     * Wait for {@code w} to be answered, looking every {@link #LIVENESS_EVERY_MS} that something still
     * holds it: an item neither queued nor in flight on a live worker would otherwise be waited on
     * forever, so it fails instead.
     */
    private void awaitAnswer(CompileWork w) throws InterruptedException {
        while (true) {
            try {
                w.compile.handle((r, e) -> null).get(LIVENESS_EVERY_MS, TimeUnit.MILLISECONDS);
                return;
            } catch (TimeoutException e) {
                if (!holds(w) && !w.done()) {
                    w.fail(new IOException("zinc worker lost the compile: it is neither queued nor in flight"));
                    return;
                }
            } catch (ExecutionException e) {
                return;
            }
        }
    }

    /** Test seam: items waiting for a worker. */
    synchronized int queued() {
        return queue.size();
    }

    /** Test seam: live workers. */
    synchronized List<CompilerWorker> workers() {
        workers.removeIf(CompilerWorker::dead);
        return List.copyOf(workers);
    }

    synchronized boolean hasReadyWorker() {
        for (CompilerWorker w : workers) {
            if (w.ready() && !w.retiring()) return true;
        }
        return false;
    }

    /** Test seam: whether {@link #close} has begun. */
    synchronized boolean closing() {
        return closed;
    }

    /**
     * Tell every worker to finish and exit, wait for them, then stop any that has not; whatever is
     * still queued has no taker any more and fails. Total: no send that cannot be made, and no
     * worker that never connected, keeps it from stopping the process and failing the queue.
     */
    void close() {
        List<CompilerWorker> live;
        synchronized (this) {
            closed = true;
            live = List.copyOf(workers);
            notifyAll();
        }
        LIVE.remove(this);
        try {
            for (CompilerWorker w : live) {
                try {
                    w.retire();
                } catch (RuntimeException e) {
                    Log.debug("zinc worker retire: " + e.getMessage());
                }
            }
            long deadline = CLOCK.nanos() + TimeUnit.MILLISECONDS.toNanos(closeWaitMillis);
            for (CompilerWorker w : live) {
                w.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - CLOCK.nanos())));
            }
        } finally {
            for (CompilerWorker w : live) {
                if (w.dead()) continue;
                try {
                    w.kill();
                } catch (RuntimeException e) {
                    Log.debug("zinc worker kill: " + e.getMessage());
                }
            }
            List<CompileWork> orphans;
            synchronized (this) {
                orphans = new ArrayList<>(queue);
                queue.clear();
            }
            for (CompileWork w : orphans) w.fail(new IOException("zinc worker pool closed"));
        }
    }

    /** One of this pool's items was answered. */
    private void settled() {
        synchronized (this) {
            items = Math.max(0, items - 1);
        }
        lastUsedNanos = CLOCK.nanos();
    }

    /** Retire when no item is on this pool, and say whether it did. Closed off the caller's thread. */
    boolean retireIfIdle() {
        boolean[] retired = {false};
        POOLS.computeIfPresent(key, (k, p) -> {
            if (p != this || busy()) return p;
            retired[0] = true;
            return null;
        });
        if (retired[0]) CompilerWorker.detached("jk-javac-retire", this::close);
        return retired[0];
    }

    private synchronized boolean busy() {
        return items > 0;
    }

    private synchronized void armReaper() {
        if (reaper == null) reaper = CompilerWorker.detached("jk-javac-linger", this::reap);
    }

    /**
     * Sleep until the linger could be over and look again: an item answered in between moves the
     * deadline, and one still on the pool keeps it. Retiring is decided under the same compute that
     * counts items in, so no item is ever handed a pool that is closing.
     */
    private void reap() {
        while (true) {
            long linger = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, JavaCompilerHost.lingerMillis()));
            long wait = linger - (CLOCK.nanos() - lastUsedNanos);
            if (wait <= 0 && latest == this && WarmLinger.keepsLatest(WarmLinger.JAVA_COMPILER)) {
                wait = Math.max(linger, TimeUnit.MINUTES.toNanos(1));
            } else if (wait <= 0) {
                if (retireNow() || POOLS.get(key) != this) return;
                wait = linger;
            }
            try {
                Thread.sleep(Duration.ofNanos(wait));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Retire on this thread when no item is on the pool. */
    private boolean retireNow() {
        boolean[] retired = {false};
        POOLS.computeIfPresent(key, (k, p) -> {
            if (p != this || busy()) return p;
            retired[0] = true;
            return null;
        });
        if (retired[0]) close();
        return retired[0];
    }

    /**
     * The workers outlive every job, so nothing else would stop them if the engine exits inside a
     * linger: a worker still compiling is stopped, so it does not go on writing classes nobody will
     * read. An idle worker leaves on its channel closing.
     */
    private static void registerHooks() {
        if (hookRegistered) return;
        synchronized (CompilerPool.class) {
            if (hookRegistered) return;
            hookRegistered = true;
            HostPressure.register("warm java compilers", CompilerPool::shedIdle);
            // Shutdown hook; reads no session.
            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                Set<CompilerWorker> busy = new LinkedHashSet<>();
                                for (CompilerPool pool : LIVE) {
                                    for (CompilerWorker w : pool.workers()) {
                                        if (!w.inflight.isEmpty()) busy.add(w);
                                    }
                                }
                                for (CompilerWorker w : busy) w.kill();
                            },
                            "jk-javac-linger-shutdown"));
        }
    }
}
