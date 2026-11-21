// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.host.Log;
import cc.jumpkick.repo.DownloadSlots;
import cc.jumpkick.run.JkThreads;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The speculative reads a {@link MavenPackageSource} runs ahead of the solver, drained on the io
 * pool by at most {@link #workers} threads. A queue rather than a thread per submission: a
 * reactor's roots or a widening pass hand over hundreds of items at once, and a parked thread per
 * item would hold its stack and closure until a worker slot freed. A failed item is only logged:
 * the solver's own read surfaces real failures.
 */
final class PrefetchPool {

    /**
     * Workers draining the queue at once: half the {@linkplain DownloadSlots#width() download
     * slots}, at most {@value #WORKERS_CAP}. Each worker's POM chain fans out across repositories
     * and BOM imports into network legs that take leg slots, so the workers are sized to keep the
     * legs busy without outnumbering them.
     */
    static final int WORKERS_CAP = 32;

    /**
     * How long a widening pass waits for its catalogs before reading the rest itself, and how long
     * {@link #quiesce} waits before it says which reads are still winding down.
     */
    static final long WAIT_MS = 30_000;

    /** How often {@link #quiesce} re-interrupts a worker that has not finished its read. */
    private static final long QUIESCE_NUDGE_MS = 1_000;

    private static final int WORKERS = workers(DownloadSlots.width());

    /** One speculative read. */
    @FunctionalInterface
    interface Work {
        void run() throws Exception;
    }

    private final Queue<Work> queue = new ConcurrentLinkedQueue<>();

    /** Workers draining {@link #queue} right now; never above {@link #WORKERS}. */
    private final AtomicInteger running = new AtomicInteger();

    /** Items submitted and not yet finished. Guards {@link #quiesce}. */
    private final AtomicInteger outstanding = new AtomicInteger();

    /** The threads draining the queue right now, so {@link #quiesce} can interrupt the read each is in. */
    private final Set<Thread> threads = ConcurrentHashMap.newKeySet();

    private final Object idle = new Object();

    /** Told once per item that ran, however it ended. */
    private final Runnable ran;

    PrefetchPool(Runnable ran) {
        this.ran = ran;
    }

    /** The worker count for {@code slotWidth} download slots. */
    static int workers(int slotWidth) {
        return Math.max(1, Math.min(WORKERS_CAP, slotWidth / 2));
    }

    /** Queue {@code work}, counted so {@link #quiesce} can wait for it. */
    void submit(Work work) {
        outstanding.incrementAndGet();
        queue.add(work);
        startWorkerIfRoom();
    }

    /** Workers draining the queue right now. */
    int workersRunning() {
        return running.get();
    }

    /** Start one more draining worker when work is queued and fewer than the cap are running. */
    private void startWorkerIfRoom() {
        while (!queue.isEmpty()) {
            int now = running.get();
            if (now >= WORKERS) return;
            if (running.compareAndSet(now, now + 1)) {
                try {
                    JkThreads.io().execute(this::drain);
                } catch (RuntimeException rejected) {
                    running.decrementAndGet();
                    while (queue.poll() != null) finish();
                    throw rejected;
                }
                return;
            }
        }
    }

    /**
     * One worker: run queued items until none is left. A worker interrupted mid-item — which is how
     * {@link #quiesce} cancels a read — leaves the rest of the queue to a fresh one.
     */
    private void drain() {
        Thread self = Thread.currentThread();
        threads.add(self);
        try {
            Work work;
            while ((work = queue.poll()) != null) {
                try {
                    work.run();
                } catch (InterruptedException e) {
                    self.interrupt();
                    return;
                } catch (Exception e) {
                    Log.debug("prefetch: best-effort warming", e);
                    if (self.isInterrupted()) return;
                } finally {
                    ran.run();
                    finish();
                }
            }
        } finally {
            threads.remove(self);
            running.decrementAndGet();
            // A submit that saw a full complement while this worker was leaving left its item behind.
            startWorkerIfRoom();
        }
    }

    private void finish() {
        if (outstanding.decrementAndGet() == 0) {
            synchronized (idle) {
                idle.notifyAll();
            }
        }
    }

    /**
     * Drop the work not yet started, cancel what is running and block until nothing is in flight. A
     * running read is cancelled by interrupting its worker, again every {@link #QUIESCE_NUDGE_MS}
     * until it has let go; a caller's own interrupt is kept for it and re-asserted once the workers
     * are gone.
     */
    void quiesce() {
        while (queue.poll() != null) finish();
        boolean interrupted = false;
        int nudges = 0;
        synchronized (idle) {
            while (outstanding.get() > 0) {
                for (Thread worker : threads) worker.interrupt();
                try {
                    idle.wait(QUIESCE_NUDGE_MS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
                if (++nudges * QUIESCE_NUDGE_MS == WAIT_MS) {
                    Log.warn("jk: still waiting for " + outstanding.get()
                            + " speculative dependency read(s) to stop after " + (WAIT_MS / 1_000) + " s");
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
