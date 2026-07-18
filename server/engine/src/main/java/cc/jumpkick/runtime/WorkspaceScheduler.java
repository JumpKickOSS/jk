// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.run.JkThreads;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;

/**
 * Workspace DAG scheduler: topological levels, optional {@code maxConcurrency} cap (1 = serial).
 * Per-unit work and presentation stay in the caller's {@link UnitTask}/{@link LevelSink}.
 */
public final class WorkspaceScheduler {

    private WorkspaceScheduler() {}

    /** A finished unit's result (or the throwable that ended it) — the bounded path's completion queue item. */
    private record Done<U, R>(U unit, R result, Throwable error) {}

    /** Build one unit, producing its result. Run concurrently on {@link JkThreads#io()}. */
    @FunctionalInterface
    public interface UnitTask<U, R> {
        R run(U unit);
    }

    /** Handles completed units. */
    @FunctionalInterface
    public interface LevelSink<U, R> {
        /**
         * After completed units: unbounded → once per topological level; bounded → once per unit.
         * Non-null return stops the schedule (fail-fast); {@code null} continues.
         */
        R after(List<U> justCompleted, List<R> results, List<U> remaining);
    }

    /** Unbounded schedule (batch-per-level). Cap {@code <= 0} form of {@link #run}. */
    public static <U, R> R run(
            List<U> units,
            Function<U, Path> dirOf,
            Map<Path, Set<Path>> edges,
            UnitTask<U, R> task,
            LevelSink<U, R> sink) {
        return run(units, dirOf, edges, task, sink, 0);
    }

    /**
     * Schedule {@code units} over {@code edges} (unit dir → deps) with at most {@code maxConcurrency}
     * in flight ({@code <= 0} = unbounded batch-per-level; {@code 1} = serial). Fail-fast when
     * {@code sink} returns non-null; otherwise {@code null} when all units finish.
     */
    public static <U, R> R run(
            List<U> units,
            Function<U, Path> dirOf,
            Map<Path, Set<Path>> edges,
            UnitTask<U, R> task,
            LevelSink<U, R> sink,
            int maxConcurrency) {
        Set<Path> unitDirs = new HashSet<>();
        for (U u : units) unitDirs.add(dirOf.apply(u));
        Set<Path> done = ConcurrentHashMap.newKeySet();
        if (maxConcurrency <= 0) {
            // Unbounded: batch-per-level (unchanged from the original scheduler).
            List<U> remaining = new ArrayList<>(units);
            while (!remaining.isEmpty()) {
                List<U> ready = remaining.stream()
                        .filter(u -> edges.getOrDefault(dirOf.apply(u), Set.of()).stream()
                                .filter(unitDirs::contains)
                                .allMatch(done::contains))
                        .toList();
                List<CompletableFuture<R>> futures = new ArrayList<>();
                for (U u : ready) futures.add(CompletableFuture.supplyAsync(() -> task.run(u), JkThreads.io()));
                List<R> results = new ArrayList<>(futures.size());
                for (CompletableFuture<R> f : futures) results.add(f.join());
                for (U u : ready) done.add(dirOf.apply(u));
                remaining.removeAll(ready);
                R stop = sink.after(ready, results, remaining);
                if (stop != null) return stop;
            }
            return null;
        }
        // Bounded: rolling window of at most maxConcurrency in-flight units, admitted across levels.
        List<U> notStarted = new ArrayList<>(units);
        BlockingQueue<Done<U, R>> completed = new LinkedBlockingQueue<>();
        int inFlight = 0;
        while (true) {
            // Admit ready, not-yet-started units until the concurrency window is full.
            while (inFlight < maxConcurrency) {
                U next = null;
                for (U u : notStarted) {
                    boolean ready = edges.getOrDefault(dirOf.apply(u), Set.of()).stream()
                            .filter(unitDirs::contains)
                            .allMatch(done::contains);
                    if (ready) {
                        next = u;
                        break;
                    }
                }
                if (next == null) break; // nothing else admittable right now
                notStarted.remove(next);
                U unit = next;
                CompletableFuture.supplyAsync(() -> task.run(unit), JkThreads.io())
                        .whenComplete((r, ex) -> completed.add(new Done<>(unit, r, ex)));
                inFlight++;
            }
            if (inFlight == 0) return null; // nothing running and nothing admittable — the DAG has drained
            Done<U, R> d;
            try {
                d = completed.take(); // wait for the next unit to finish
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
            inFlight--;
            if (d.error() != null) {
                throw d.error() instanceof CompletionException ce ? ce : new CompletionException(d.error());
            }
            done.add(dirOf.apply(d.unit()));
            R stop = sink.after(List.of(d.unit()), Collections.singletonList(d.result()), List.copyOf(notStarted));
            if (stop != null) return stop; // fail-fast; any in-flight units drain in the background
        }
    }
}
