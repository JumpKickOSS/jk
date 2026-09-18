// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.run.JkThreads;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * The legs of a {@link RepoGroup} fetch fanned out across repositories: one network leg per
 * repository, each under a {@link DownloadSlots#acquireLeg(String) leg slot} of its host, handed to
 * the io pool and settled — cancelled, interrupted, waited out — once an earlier repository has
 * answered. {@link FetchFanOut} decides whose answer counts; this class runs the legs.
 */
final class RepoLegs {

    private RepoLegs() {}

    /** One repository's network leg for a fetch. */
    @FunctionalInterface
    interface LegWork<T> {
        T fetch(MavenRepo repo) throws Exception;
    }

    /**
     * Run {@code work} against each of {@code repos} at once, one leg per repository, each under a
     * {@link DownloadSlots#acquireLeg(String) leg slot} of its host: on the io pool when several
     * repositories are asked, inline when there is one, since a single-repository group pays for no
     * thread hand-off. The slots are taken here, on the calling thread, before a leg is handed to the
     * pool, so a fan-out wider than a host's queue parks its later legs unsubmitted rather than as
     * one waiting thread each — and in two passes: first every leg whose host has room right now,
     * then the rest, each submitted as soon as its own host frees a slot, so a repository whose queue
     * is full holds back only the legs bound for it while the others already run. A pooled leg runs
     * under the calling thread's session, so {@code --offline} and {@code --force} reach it.
     */
    static <T> List<Leg<T>> legs(List<MavenRepo> repos, LegWork<T> work) throws InterruptedException {
        if (repos.size() == 1) {
            MavenRepo repo = repos.getFirst();
            String host = hostOf(repo);
            DownloadSlots.acquireLeg(host);
            CompletableFuture<T> inline = new CompletableFuture<>();
            try {
                inline.complete(work.fetch(repo));
            } catch (Exception e) {
                inline.completeExceptionally(e);
            } finally {
                DownloadSlots.releaseLeg(host);
            }
            return List.of(Leg.finished(inline));
        }
        var session = SessionContext.current();
        List<Leg<T>> out = new ArrayList<>(repos.size());
        boolean[] submitted = new boolean[repos.size()];
        for (int i = 0; i < repos.size(); i++) {
            out.add(Leg.finished(new CompletableFuture<>()));
            MavenRepo repo = repos.get(i);
            String host = hostOf(repo);
            if (DownloadSlots.tryAcquireLeg(host)) {
                out.set(
                        i,
                        trackedLeg(JkThreads.io(), host, () -> SessionContext.where(session, () -> work.fetch(repo))));
                submitted[i] = true;
            }
        }
        int waiting = repos.size();
        for (boolean b : submitted) if (b) waiting--;
        // Rotate over the legs still waiting rather than block on the first: a host whose queue frees
        // next gets its leg submitted whatever its place in repository order.
        while (waiting > 0) {
            for (int i = 0; i < repos.size(); i++) {
                if (submitted[i]) continue;
                MavenRepo repo = repos.get(i);
                String host = hostOf(repo);
                if (!DownloadSlots.tryAcquireLeg(host, LEG_SLOT_POLL_MS)) continue;
                out.set(
                        i,
                        trackedLeg(JkThreads.io(), host, () -> SessionContext.where(session, () -> work.fetch(repo))));
                submitted[i] = true;
                waiting--;
            }
        }
        return out;
    }

    /** How long a fan-out waits on one host's queue before looking at the next host's. */
    private static final long LEG_SLOT_POLL_MS = 10;

    /** The host whose leg slots and request permits a repository's legs take; empty for a local tree. */
    private static String hostOf(MavenRepo repo) {
        String host = repo.baseUrl().getHost();
        return host == null ? "" : host;
    }

    /**
     * Hand {@code work} to {@code pool} holding the leg slot for {@code host} the caller has already
     * taken, and give the slot back exactly once: as the leg ends, or at once when the leg is
     * cancelled before it started, since a body that never runs cannot release anything. The fetch
     * loop cancels the legs it no longer needs the moment an earlier repository answers, so a slot
     * tied to the body alone would leak on every such cancel until nothing could fetch at all.
     */
    static <T> CompletableFuture<T> pooledLeg(Executor pool, String host, Callable<T> work) {
        return trackedLeg(pool, host, work).future();
    }

    /**
     * One leg of a fan-out: its answer, the moment its body has returned or will never run — which
     * a cancelled {@link Future} does not tell, since cancelling only asks the leg to stop — and the
     * thread running it, so {@link #settle} can interrupt a read that lost to an earlier answer.
     */
    static final class Leg<T> {
        private final CompletableFuture<T> future;
        private final CountDownLatch finished = new CountDownLatch(1);
        private @Nullable Thread runner;

        private Leg(CompletableFuture<T> future) {
            this.future = future;
        }

        /** A leg whose body already ran (or never will): nothing for {@link #settle} to wait for. */
        static <T> Leg<T> finished(CompletableFuture<T> future) {
            Leg<T> leg = new Leg<>(future);
            leg.finished.countDown();
            return leg;
        }

        CompletableFuture<T> future() {
            return future;
        }

        private synchronized void running(@Nullable Thread thread) {
            runner = thread;
        }

        /** Interrupt the body if it is running right now; a thread that has moved on is left alone. */
        private synchronized void interruptRunner() {
            if (runner != null) runner.interrupt();
        }
    }

    /** {@link #pooledLeg} with the leg's completion latch and runner, for the fan-out's {@link #settle}. */
    private static <T> Leg<T> trackedLeg(Executor pool, String host, Callable<T> work) {
        Leg<T> leg = new Leg<>(new CompletableFuture<>());
        CompletableFuture<T> future = leg.future;
        // Whoever flips this owns the release: the body when it starts, or the cancel that beat it.
        AtomicBoolean owned = new AtomicBoolean();
        legsInFlight.incrementAndGet();
        future.whenComplete((r, e) -> {
            if (future.isCancelled() && owned.compareAndSet(false, true)) legEnded(host, leg);
        });
        Runnable body = () -> {
            if (!owned.compareAndSet(false, true)) return;
            leg.running(Thread.currentThread());
            try {
                future.complete(work.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            } finally {
                leg.running(null);
                legEnded(host, leg);
            }
        };
        try {
            pool.execute(body);
        } catch (RuntimeException rejected) {
            if (owned.compareAndSet(false, true)) legEnded(host, leg);
            throw rejected;
        }
        return leg;
    }

    /**
     * End every leg the walk did not wait for: a leg still running is interrupted, and the call
     * returns only once each has stopped, so no fetch that lost to an earlier answer is still
     * writing into its repository's store after the caller has moved on. A caller's own interrupt
     * is kept for it and re-asserted afterwards.
     */
    static void settle(List<? extends Leg<?>> legs) {
        for (Leg<?> leg : legs) {
            if (leg.future.isDone()) continue;
            leg.future.cancel(true);
            leg.interruptRunner();
        }
        boolean interrupted = false;
        for (Leg<?> leg : legs) {
            while (true) {
                try {
                    leg.finished.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void legEnded(String host, Leg<?> leg) {
        legsInFlight.decrementAndGet();
        DownloadSlots.releaseLeg(host);
        leg.finished.countDown();
    }

    /** Pooled legs submitted and not yet finished, across every group in the process. */
    private static final AtomicInteger legsInFlight = new AtomicInteger();

    /** Test seam: pooled legs submitted and not yet finished, across every group in the process. */
    static int legsInFlight() {
        return legsInFlight.get();
    }

    /** The result of a fetch leg, rethrowing what the leg threw: not-found, transport or interrupt. */
    static <T> T await(Future<T> leg) throws IOException, InterruptedException {
        try {
            return leg.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof InterruptedException ie) throw ie;
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new IOException(cause);
        }
    }
}
