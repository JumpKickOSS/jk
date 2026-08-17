// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.run.JkThreads;
import cc.jumpkick.run.SessionCancel;
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
import java.util.function.BooleanSupplier;
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
     * {@code sink} returns non-null; otherwise {@code null} when all units finish. Stops admitting
     * (and does not join the remaining DAG) when {@link SessionCancel} is set.
     */
    public static <U, R> R run(
            List<U> units,
            Function<U, Path> dirOf,
            Map<Path, Set<Path>> edges,
            UnitTask<U, R> task,
            LevelSink<U, R> sink,
            int maxConcurrency) {
        return run(units, dirOf, edges, task, sink, maxConcurrency, SessionCancel::cancelled);
    }

    /**
     * As {@link #run(List, Function, Map, UnitTask, LevelSink, int)} with an explicit cancel probe
     * (tests). When {@code cancelled} is true, no further units are admitted (queued-but-unstarted
     * tasks no-op via an in-task gate), in-flight units are drained for a bounded window (see
     * {@link #CANCEL_DRAIN_MS}) so their module events land before this method returns, and then
     * {@code null} is returned. {@code CompletableFuture.cancel(true)} is deliberately NOT used on
     * the cancel path: it settles the future instantly while the supplier keeps running, which let
     * module-finish events fire after the workspace-finish event (JK-2097). Real stoppage is
     * cooperative — SessionCancel checks inside plans plus JobWorkers process kills — which
     * settles tasks quickly; the bound keeps cancel from ever hanging on a wedged step.
     */
    public static <U, R> R run(
            List<U> units,
            Function<U, Path> dirOf,
            Map<Path, Set<Path>> edges,
            UnitTask<U, R> task,
            LevelSink<U, R> sink,
            int maxConcurrency,
            BooleanSupplier cancelled) {
        BooleanSupplier stop = cancelled == null ? () -> false : cancelled;
        Set<Path> unitDirs = new HashSet<>();
        for (U u : units) unitDirs.add(dirOf.apply(u));
        Set<Path> done = ConcurrentHashMap.newKeySet();
        if (maxConcurrency <= 0) {
            List<U> remaining = new ArrayList<>(units);
            while (!remaining.isEmpty()) {
                if (stop.getAsBoolean()) return null;
                List<U> ready = remaining.stream()
                        .filter(u -> edges.getOrDefault(dirOf.apply(u), Set.of()).stream()
                                .filter(unitDirs::contains)
                                .allMatch(done::contains))
                        .toList();
                // Cycle / filtered-dep hole: remaining units but none are ready → spin forever
                // without this guard (BuildPlan.topoSort detects cycles; this path did not).
                if (ready.isEmpty()) {
                    throw unsatisfiable(remaining, dirOf, edges, unitDirs, done);
                }
                List<CompletableFuture<R>> futures = new ArrayList<>();
                for (U u : ready) {
                    if (stop.getAsBoolean()) {
                        drainCancelled(futures);
                        return null;
                    }
                    futures.add(CompletableFuture.supplyAsync(() -> gated(stop, task, u), JkThreads.io()));
                }
                List<R> results = new ArrayList<>(futures.size());
                for (CompletableFuture<R> f : futures) {
                    if (stop.getAsBoolean()) {
                        drainCancelled(futures);
                        return null;
                    }
                    results.add(f.join());
                }
                for (U u : ready) done.add(dirOf.apply(u));
                remaining.removeAll(ready);
                R sinkStop = sink.after(ready, results, remaining);
                if (sinkStop != null) return sinkStop;
            }
            return null;
        }
        List<U> notStarted = new ArrayList<>(units);
        BlockingQueue<Done<U, R>> completed = new LinkedBlockingQueue<>();
        Set<CompletableFuture<?>> inflight = ConcurrentHashMap.newKeySet();
        int inFlight = 0;
        while (true) {
            if (stop.getAsBoolean()) {
                drainCancelled(inflight);
                return null;
            }
            while (inFlight < maxConcurrency && !stop.getAsBoolean()) {
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
                if (next == null) break;
                notStarted.remove(next);
                U unit = next;
                CompletableFuture<R> f = CompletableFuture.supplyAsync(() -> gated(stop, task, unit), JkThreads.io());
                inflight.add(f);
                f.whenComplete((r, ex) -> {
                    inflight.remove(f);
                    completed.add(new Done<>(unit, r, ex));
                });
                inFlight++;
            }
            if (stop.getAsBoolean()) {
                drainCancelled(inflight);
                return null;
            }
            if (inFlight == 0) {
                if (!notStarted.isEmpty()) {
                    throw unsatisfiable(notStarted, dirOf, edges, unitDirs, done);
                }
                return null;
            }
            Done<U, R> d;
            try {
                d = completed.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelAll(inflight);
                if (stop.getAsBoolean()) return null;
                throw new CompletionException(e);
            }
            inFlight--;
            if (d.error() != null) {
                throw d.error() instanceof CompletionException ce ? ce : new CompletionException(d.error());
            }
            done.add(dirOf.apply(d.unit()));
            R sinkStop = sink.after(List.of(d.unit()), Collections.singletonList(d.result()), List.copyOf(notStarted));
            if (sinkStop != null) {
                cancelAll(inflight);
                return sinkStop;
            }
        }
    }

    /**
     * Bounded settle window for the cancel path — long enough for cooperative stoppage
     * (SessionCancel + JobWorkers kills, sub-second by contract) with headroom, short enough
     * that a wedged step can never pin cancel.
     */
    static final long CANCEL_DRAIN_MS = 2_000L;

    /** Admission gate: a queued task starting after cancel must do nothing (and emit nothing). */
    private static <U, R> R gated(BooleanSupplier stop, UnitTask<U, R> task, U unit) {
        if (stop.getAsBoolean()) return null;
        return task.run(unit);
    }

    /** Fail-fast path: in-flight modules keep building; only queued-not-started are prevented. */
    private static void cancelAll(Iterable<? extends CompletableFuture<?>> futures) {
        for (CompletableFuture<?> f : futures) {
            f.cancel(true);
        }
    }

    /** Cancel path: wait (bounded) for in-flight tasks so their events precede our return. */
    private static void drainCancelled(Iterable<? extends CompletableFuture<?>> futures) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(CANCEL_DRAIN_MS);
        for (CompletableFuture<?> f : futures) {
            long left = deadline - System.nanoTime();
            if (left <= 0) return; // residual: a task outliving the drain may emit late events
            try {
                f.get(left, java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // failed / timed out — best-effort drain
            }
        }
    }

    /**
     * Fail closed when units remain but none are ready (cycle, or a dep filtered out of the unit
     * set while still named in {@code edges}).
     */
    static <U> IllegalStateException unsatisfiable(
            List<U> stuck, Function<U, Path> dirOf, Map<Path, Set<Path>> edges, Set<Path> unitDirs, Set<Path> done) {
        StringBuilder msg = new StringBuilder("workspace schedule unsatisfiable: stuck units");
        for (U u : stuck) {
            Path dir = dirOf.apply(u);
            List<String> unmet = edges.getOrDefault(dir, Set.of()).stream()
                    .filter(unitDirs::contains)
                    .filter(d -> !done.contains(d))
                    .map(Path::toString)
                    .sorted()
                    .toList();
            msg.append("\n  - ").append(dir);
            if (!unmet.isEmpty()) {
                msg.append(" waits on ").append(unmet);
            } else {
                msg.append(" (no ready deps among remaining units — likely a cycle)");
            }
        }
        return new IllegalStateException(msg.toString());
    }
}
