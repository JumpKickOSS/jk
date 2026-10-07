// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.run.ContextPropagator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs the member solves of a lock's partition pass a few at a time. A solve is latency-bound on
 * POM fetches, so several overlap well; each is charged an estimate of the heap its graph holds,
 * and one starts only while the charges in flight fit the heap the lock could spare when the pass
 * began. Solves start in the order given and their results come back in that order, so the lock
 * they assemble is the one a one-at-a-time pass writes. A failure is the first in that order.
 */
final class MemberSolves {

    /** The most member solves in flight at once. */
    static final int MAX_AT_ONCE = 4;

    /**
     * Heap charged per module of a member's graph while its solve runs: what the engine's admission
     * charges a lock per declared dependency.
     */
    static final long BYTES_PER_MODULE = 64L << 10;

    /** Heap left untouched when sizing the budget, as the engine's admission keeps it. */
    static final long RESERVE_BYTES = 32L << 20;

    /** One member's solve: what it is charged, and the solve. */
    record Job<T>(long bytes, Task<T> task) {}

    /** A solve that reads the repositories and may be interrupted. */
    interface Task<T> {
        T call() throws IOException, InterruptedException;
    }

    private final int slots;
    private final long budget;
    private int running;
    private long charged;
    private int nextToStart;
    private int peak;

    /**
     * @param slots the most solves in flight at once, at least one
     * @param budget the bytes the solves in flight may be charged together; a solve that alone
     *     exceeds it still runs, by itself
     */
    MemberSolves(int slots, long budget) {
        this.slots = Math.max(1, slots);
        this.budget = Math.max(0, budget);
    }

    /** Sized from this JVM: half the heap free of what is in use and the reserve, on up to {@link #MAX_AT_ONCE} cores. */
    static MemberSolves forRuntime() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long spare = rt.maxMemory() - used - RESERVE_BYTES;
        return new MemberSolves(Math.min(MAX_AT_ONCE, rt.availableProcessors()), spare / 2);
    }

    /** Every job's result, in order; the first job in order to fail throws, and the rest are cancelled. */
    <T> List<T> runAll(List<Job<T>> jobs) throws IOException, InterruptedException {
        List<Future<T>> futures = new ArrayList<>(jobs.size());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < jobs.size(); i++) {
                int index = i;
                Job<T> job = jobs.get(i);
                // Wrapped here, on the submitting thread: the session and the metadata flags it
                // carries are this lock's, and the solve reads them on the worker.
                Callable<T> body = ContextPropagator.wrapCallable(() -> run(index, job));
                futures.add(pool.submit(body));
            }
            List<T> out = new ArrayList<>(jobs.size());
            try {
                for (Future<T> f : futures) out.add(f.get());
            } catch (ExecutionException e) {
                for (Future<T> f : futures) f.cancel(true);
                Throwable cause = e.getCause();
                if (cause instanceof IOException io) throw io;
                if (cause instanceof InterruptedException ie) throw ie;
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error err) throw err;
                throw new IOException(cause);
            } catch (InterruptedException e) {
                for (Future<T> f : futures) f.cancel(true);
                throw e;
            }
            return out;
        }
    }

    /** The most solves that were in flight at once during the last {@link #runAll}. */
    synchronized int peak() {
        return peak;
    }

    private <T> T run(int index, Job<T> job) throws IOException, InterruptedException {
        admit(index, job.bytes());
        try {
            return job.task().call();
        } finally {
            release(job.bytes());
        }
    }

    private synchronized void admit(int index, long bytes) throws InterruptedException {
        while (index != nextToStart || (running > 0 && (running >= slots || charged + bytes > budget))) wait();
        nextToStart++;
        running++;
        charged += bytes;
        peak = Math.max(peak, running);
        notifyAll();
    }

    private synchronized void release(long bytes) {
        running--;
        charged -= bytes;
        notifyAll();
    }
}
