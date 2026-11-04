// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.task.TransferBoard;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A known set of fetches, all at once: a tool's resolved closure, a depth of a worker's POM walk.
 * The answers come back in the order asked, so what the caller builds from them is what a
 * one-at-a-time loop builds. The first failure to happen, or an interrupt, cancels every fetch
 * still running at once; the call returns once they have stopped (or a short bound passes), and
 * throws the failure the first fetch in order met, as a one-at-a-time loop would have.
 */
public final class ArtifactFanOut {

    /** How long a cancelled set is given to stop before the call returns without it. */
    static final Duration CANCEL_GRACE = Duration.ofSeconds(5);

    private ArtifactFanOut() {}

    /** One fetch of the set. */
    @FunctionalInterface
    public interface Fetch<T> {
        T fetch() throws IOException, InterruptedException;
    }

    /** One coordinate's answer, kept beside the coordinate so the board can tell a download. */
    private record Hit(Coordinate coord, Optional<RepoGroup.RepoFetched> fetched) {}

    /**
     * {@code coords}' artifacts, fetched at once as a declared set on the run's board, each empty
     * where no repository has it.
     */
    public static List<Optional<RepoGroup.RepoFetched>> artifacts(RepoGroup repos, List<Coordinate> coords)
            throws IOException, InterruptedException {
        TransferBoard board = SessionContext.current().io().transfers();
        List<Fetch<Hit>> fetches = new ArrayList<>(coords.size());
        for (Coordinate c : coords) fetches.add(() -> new Hit(c, repos.tryFetchArtifact(c)));
        List<Optional<RepoGroup.RepoFetched>> out = new ArrayList<>(coords.size());
        for (Hit h : all(
                fetches, h -> h.fetched().isPresent() && board.landed(h.coord().toString()))) {
            out.add(h.fetched());
        }
        return out;
    }

    /**
     * Every fetch at once, as a declared set on the run's board: {@code downloaded} says whether an
     * answer's bytes came down the wire (else the store already held them), so a set of files
     * already on disk never shows as a download. A set of one runs on the caller.
     */
    public static <T> List<T> all(List<Fetch<T>> fetches, Predicate<T> downloaded)
            throws IOException, InterruptedException {
        try (TransferBoard.Plan plan = SessionContext.current().io().transfers().plan(fetches.size())) {
            List<Fetch<T>> settling = new ArrayList<>(fetches.size());
            for (Fetch<T> f : fetches) {
                settling.add(() -> {
                    T answer = f.fetch();
                    plan.settled(downloaded.test(answer));
                    return answer;
                });
            }
            if (settling.size() <= 1) {
                List<T> one = new ArrayList<>(1);
                for (Fetch<T> f : settling) one.add(f.fetch());
                return one;
            }
            return concurrently(settling);
        }
    }

    /**
     * Every fetch at once on the I/O pool, answered in order, with no set declared: for work whose
     * parts are not files (a tool each), whose own fetches declare theirs.
     */
    public static <T> List<T> concurrently(List<Fetch<T>> fetches) throws IOException, InterruptedException {
        CompletionService<T> completions = new ExecutorCompletionService<>(JkThreads.io());
        CountDownLatch stopped = new CountDownLatch(fetches.size());
        List<Future<T>> pending = new ArrayList<>(fetches.size());
        for (Fetch<T> f : fetches) {
            pending.add(completions.submit(() -> {
                try {
                    return f.fetch();
                } finally {
                    stopped.countDown();
                }
            }));
        }
        Future<T> firstFailed = null;
        try {
            // In the order they finish: a fast failure cancels the rest without waiting behind a
            // slow fetch earlier in the list.
            for (int i = 0; i < pending.size() && firstFailed == null; i++) {
                Future<T> done = completions.take();
                if (failed(done)) firstFailed = done;
            }
        } finally {
            if (firstFailed != null || Thread.currentThread().isInterrupted() || stopped.getCount() > 0) {
                cancelAndWait(pending, stopped);
            }
        }
        if (firstFailed == null) {
            List<T> answers = new ArrayList<>(pending.size());
            for (Future<T> f : pending) answers.add(answer(f));
            return answers;
        }
        // The failure a one-at-a-time loop would have met: the first in order among those that
        // finished on their own, else the one that stopped the set.
        for (Future<T> f : pending) {
            if (!f.isCancelled() && f.isDone() && failed(f)) answer(f);
        }
        answer(firstFailed);
        throw new IllegalStateException("unreachable: a failed fetch threw");
    }

    /** Cancel every fetch still running and give them a moment to stop: nothing of the set outlives the call. */
    private static void cancelAndWait(List<? extends Future<?>> pending, CountDownLatch stopped) {
        for (Future<?> f : pending) f.cancel(true);
        boolean interrupted = Thread.interrupted();
        try {
            stopped.await(CANCEL_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            interrupted = true;
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static boolean failed(Future<?> f) {
        if (f.isCancelled()) return true;
        try {
            f.get();
            return false;
        } catch (ExecutionException e) {
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    /** A finished or cancelled fetch's answer, or the failure it met. */
    private static <T> T answer(Future<T> f) throws IOException, InterruptedException {
        try {
            if (f.isCancelled()) throw new InterruptedException("fetch cancelled");
            return f.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof InterruptedException ie) throw ie;
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new IOException(cause);
        }
    }
}
