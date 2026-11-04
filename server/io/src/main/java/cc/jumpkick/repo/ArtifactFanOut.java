// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.task.TransferBoard;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * A known set of fetches, all at once: a tool's resolved closure, a depth of a worker's POM walk.
 * The set is declared on the run's board, so its downloads are priced and drawn like a sync's, and
 * the answers come back in the order asked, so what the caller builds from them is what a
 * one-at-a-time loop builds. A failure is thrown as the first fetch in order that met one would
 * have thrown it, once every fetch has ended: nothing of the set outlives the call.
 */
public final class ArtifactFanOut {

    private ArtifactFanOut() {}

    /** One fetch of the set. */
    @FunctionalInterface
    public interface Fetch<T> {
        T fetch() throws IOException, InterruptedException;
    }

    /** {@code coords}' artifacts, fetched at once, each empty where no repository has it. */
    public static List<Optional<RepoGroup.RepoFetched>> artifacts(RepoGroup repos, List<Coordinate> coords)
            throws IOException, InterruptedException {
        List<Fetch<Optional<RepoGroup.RepoFetched>>> fetches = new ArrayList<>(coords.size());
        for (Coordinate c : coords) fetches.add(() -> repos.tryFetchArtifact(c));
        return all(fetches);
    }

    /** Every fetch at once on the I/O pool, answered in order; a set of one runs on the caller. */
    public static <T> List<T> all(List<Fetch<T>> fetches) throws IOException, InterruptedException {
        try (TransferBoard.Plan plan = SessionContext.current().io().transfers().plan(fetches.size())) {
            if (fetches.size() <= 1) {
                List<T> one = new ArrayList<>(1);
                for (Fetch<T> f : fetches) {
                    one.add(f.fetch());
                    plan.settled();
                }
                return one;
            }
            List<Fetch<T>> settling = new ArrayList<>(fetches.size());
            for (Fetch<T> f : fetches) {
                settling.add(() -> {
                    try {
                        return f.fetch();
                    } finally {
                        plan.settled();
                    }
                });
            }
            return concurrently(settling);
        }
    }

    /**
     * Every fetch at once on the I/O pool, answered in order, with no set declared: for work whose
     * parts are not files (a tool each), whose own fetches declare theirs.
     */
    public static <T> List<T> concurrently(List<Fetch<T>> fetches) throws IOException, InterruptedException {
        List<Future<T>> pending = new ArrayList<>(fetches.size());
        for (Fetch<T> f : fetches) pending.add(JkThreads.io().submit(f::fetch));
        return join(pending);
    }

    /**
     * The answers in order. On the first failure in order, or an interrupt, every fetch still
     * running is cancelled (interrupted) at once: a cancelled sync must not wait out tens of MB it
     * will never use, and a failed set has no use for the rest of it.
     */
    private static <T> List<T> join(List<Future<T>> pending) throws IOException, InterruptedException {
        List<T> done = new ArrayList<>(pending.size());
        boolean complete = false;
        try {
            for (Future<T> f : pending) {
                try {
                    done.add(f.get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException io) throw io;
                    if (cause instanceof InterruptedException ie) throw ie;
                    if (cause instanceof RuntimeException re) throw re;
                    if (cause instanceof Error err) throw err;
                    throw new IOException(cause);
                }
            }
            complete = true;
            return done;
        } finally {
            if (!complete) {
                for (Future<T> f : pending) f.cancel(true);
            }
        }
    }
}
