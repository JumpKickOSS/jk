// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

/**
 * One request per key at a time: a caller that asks for what another is already fetching waits for
 * that fetch and takes its answer, rather than asking the network a second time. A lock resolves its
 * main and test graphs at once, and both reach the same POMs and catalogs; without this, each was
 * asked for twice.
 *
 * <p>Nothing is remembered once a fetch ends: the store and the memos above this answer the next
 * caller. A waiter takes the leader's failure as its own unless {@code retried} says the failure was
 * the leader's alone (its interrupt, its abort), and then fetches itself.
 */
final class SingleFlight<V> {

    /** The fetch a key stands for. */
    @FunctionalInterface
    interface Call<V> {
        V call() throws IOException, InterruptedException;
    }

    private final ConcurrentHashMap<String, CompletableFuture<V>> flights = new ConcurrentHashMap<>();
    private final Predicate<Throwable> retried;

    /** {@code retried}: a leader failure the waiter does not share, so it fetches itself. */
    SingleFlight(Predicate<Throwable> retried) {
        this.retried = Objects.requireNonNull(retried, "retried");
    }

    /** {@code call}'s answer, from this caller's own fetch or from the one already running for {@code key}. */
    V run(String key, Call<V> call) throws IOException, InterruptedException {
        while (true) {
            CompletableFuture<V> mine = new CompletableFuture<>();
            CompletableFuture<V> theirs = flights.putIfAbsent(key, mine);
            if (theirs == null) {
                try {
                    V value = call.call();
                    mine.complete(value);
                    return value;
                } catch (Throwable failure) {
                    mine.completeExceptionally(failure);
                    throw failure;
                } finally {
                    flights.remove(key, mine);
                }
            }
            try {
                return theirs.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof InterruptedException
                        || cause instanceof CancellationException
                        || retried.test(cause)) {
                    continue;
                }
                if (cause instanceof IOException io) throw io;
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error err) throw err;
                throw new IOException(cause);
            }
        }
    }

    /** Callers parked on {@code key}'s fetch now; tests wait on it. */
    int waiters(String key) {
        CompletableFuture<V> flight = flights.get(key);
        return flight == null ? 0 : flight.getNumberOfDependents();
    }

    /** Fetches running now; tests read it. */
    int inFlight() {
        return flights.size();
    }
}
