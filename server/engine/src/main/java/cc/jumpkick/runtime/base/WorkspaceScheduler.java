// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.host.Log;
import cc.jumpkick.run.JkThreads;
import cc.jumpkick.run.SessionCancel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

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

    /**
     * Build one unit, calling {@code artifactsReady} the moment its cross-module artifacts
     * (package-jar / package-assembly) are terminal — usually well before the unit's tests and
     * terminal tails finish. Admission of dependents keys on that signal, not on completion:
     * Mill/Gradle-shaped edges, where a dependent's compile waits on the upstream
     * artifact and never on the upstream suite. Calling it more than once is harmless; a task
     * that never calls it (compile/package failed, or no package steps) implicitly publishes on
     * completion so admission can never wedge — the failed case is then handled by the sink's
     * fail-fast, or by the dependent's own accurate "sibling not built" failure.
     */
    @FunctionalInterface
    public interface PhasedUnitTask<U, R> {
        R run(U unit, Runnable artifactsReady);
    }

    /** Handles completed units. */
    @FunctionalInterface
    public interface LevelSink<U, R> {
        /**
         * After completed units: unbounded → once per topological level; bounded → once per unit.
         * Every entry of {@code results} is a unit that ran — a unit the cancel stopped at its gate
         * never reaches here. Non-null return stops the schedule (fail-fast); {@code null}
         * continues.
         */
        @Nullable
        R after(List<U> justCompleted, List<R> results, List<U> remaining);
    }

    /** Unbounded schedule (batch-per-level). Cap {@code <= 0} form of {@link #run}. */
    public static <U, R> @Nullable R run(
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
    public static <U, R> @Nullable R run(
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
     * module-finish events fire after the workspace-finish event. Real stoppage is
     * cooperative — SessionCancel checks inside plans plus JobWorkers process kills — which
     * settles tasks quickly; the bound keeps cancel from ever hanging on a wedged step.
     */
    public static <U, R> @Nullable R run(
            List<U> units,
            Function<U, Path> dirOf,
            Map<Path, Set<Path>> edges,
            UnitTask<U, R> task,
            LevelSink<U, R> sink,
            int maxConcurrency,
            BooleanSupplier cancelled) {
        return run(units, dirOf, edges, (u, ready) -> task.run(u), sink, maxConcurrency, cancelled);
    }

    /** As {@link #run(List, Function, Map, UnitTask, LevelSink, int, BooleanSupplier)} with phase gates. */
    public static <U, R> @Nullable R run(
            List<U> units,
            Function<U, Path> dirOf,
            Map<Path, Set<Path>> edges,
            PhasedUnitTask<U, R> task,
            LevelSink<U, R> sink,
            int maxConcurrency,
            BooleanSupplier cancelled) {
        return run(units, dirOf, edges, task, sink, maxConcurrency, cancelled, u -> false);
    }

    /**
     * As above, where a unit {@code awaitsCompletion} accepts is admitted only once every prereq
     * has <em>finished</em>, tests and terminal tails included — not when its artifacts are ready.
     * That is the workspace root running its after-build scripts over what the members produced:
     * a client's native binary is a terminal tail, and a dist assembled at artifact-ready time
     * shipped the previous build's client beside this build's engine.
     */
    public static <U, R> @Nullable R run(
            List<U> units,
            Function<U, Path> dirOf,
            Map<Path, Set<Path>> edges,
            PhasedUnitTask<U, R> task,
            LevelSink<U, R> sink,
            int maxConcurrency,
            BooleanSupplier cancelled,
            Predicate<U> awaitsCompletion) {
        BooleanSupplier stop = cancelled == null ? () -> false : cancelled;
        Set<Path> unitDirs = new HashSet<>();
        for (U u : units) unitDirs.add(dirOf.apply(u));
        Set<Path> done = ConcurrentHashMap.newKeySet();
        if (maxConcurrency <= 0) {
            return runUnbounded(units, dirOf, edges, task, sink, stop, unitDirs, done);
        }
        // Critical-path-first admission: the ready scan below takes the FIRST ready
        // unit, so order the backlog by longest remaining dependent chain, descending. With 13
        // units ready at the widest level, declaration order would start leaf plugins ahead of
        // the client-io → io → resolver → toolchain → engine → cli spine that dominates the wall.
        // Stable sort keeps declaration order among equals.
        List<U> notStarted = new ArrayList<>(units);
        Map<Path, Integer> height = dependentChainHeight(units, dirOf, edges, unitDirs);
        notStarted.sort(Comparator.comparingInt((U u) -> -height.getOrDefault(dirOf.apply(u), 0)));
        // Events: a Done per completed unit, or a Path per artifact-publish. Admission keys on
        // artifactsReady, so a dependent starts while its prereq's tests still run;
        // completion accounting (sink, fail-fast, the concurrency cap) stays on Done.
        BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        Set<Path> artifactsReady = ConcurrentHashMap.newKeySet();
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
                    Set<Path> gate = awaitsCompletion.test(u) ? done : artifactsReady;
                    boolean ready = edges.getOrDefault(dirOf.apply(u), Set.of()).stream()
                            .filter(unitDirs::contains)
                            .allMatch(gate::contains);
                    if (ready) {
                        next = u;
                        break;
                    }
                }
                if (next == null) break;
                notStarted.remove(next);
                U unit = next;
                Path unitDir = dirOf.apply(unit);
                Runnable publish = () -> {
                    if (artifactsReady.add(unitDir)) events.add(unitDir);
                };
                CompletableFuture<R> f =
                        CompletableFuture.supplyAsync(() -> gated(stop, task, unit, publish), JkThreads.io());
                inflight.add(f);
                f.whenComplete((r, ex) -> {
                    inflight.remove(f);
                    events.add(new Done<>(unit, r, ex));
                });
                inFlight++;
            }
            if (stop.getAsBoolean()) {
                drainCancelled(inflight);
                return null;
            }
            if (inFlight == 0) {
                if (!notStarted.isEmpty()) {
                    throw unsatisfiable(notStarted, dirOf, edges, unitDirs, artifactsReady);
                }
                return null;
            }
            Object event;
            try {
                event = events.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelAll(inflight);
                if (stop.getAsBoolean()) return null;
                throw new CompletionException(e);
            }
            if (!(event instanceof Done)) {
                continue; // artifact publish — loop back to admit newly-unblocked units
            }
            @SuppressWarnings("unchecked")
            Done<U, R> d = (Done<U, R>) event;
            inFlight--;
            if (d.error() != null) {
                throw d.error() instanceof CompletionException ce ? ce : new CompletionException(d.error());
            }
            if (d.result() == null) {
                // The gate saw the cancel after admission and ran nothing. This is the cancel path
                // reached one event late: settle the rest, hand the sink nothing.
                drainCancelled(inflight);
                return null;
            }
            done.add(dirOf.apply(d.unit()));
            // Completion always publishes: a unit that failed before its package steps (or has
            // none) must still unblock — or accurately fail — its dependents, never wedge them.
            artifactsReady.add(dirOf.apply(d.unit()));
            R sinkStop = sink.after(List.of(d.unit()), Collections.singletonList(d.result()), List.copyOf(notStarted));
            if (sinkStop != null) {
                cancelAll(inflight);
                return sinkStop;
            }
        }
    }

    /**
     * The batch-per-level schedule: every ready unit of a level runs at once, the sink sees the
     * level as one batch, and a cancel — before, during or at the gate of a level — ends the
     * schedule with {@code null} and nothing handed to the sink.
     */
    private static <U, R> @Nullable R runUnbounded(
            List<U> units,
            Function<U, Path> dirOf,
            Map<Path, Set<Path>> edges,
            PhasedUnitTask<U, R> task,
            LevelSink<U, R> sink,
            BooleanSupplier stop,
            Set<Path> unitDirs,
            Set<Path> done) {
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
                // Batch-per-level path: no early admission to feed, publish is a no-op.
                futures.add(CompletableFuture.supplyAsync(() -> gated(stop, task, u, () -> {}), JkThreads.io()));
            }
            List<R> results = new ArrayList<>(futures.size());
            for (CompletableFuture<R> f : futures) {
                if (stop.getAsBoolean()) {
                    drainCancelled(futures);
                    return null;
                }
                R result = f.join();
                if (result == null) {
                    // The gate saw the cancel after admission and ran nothing: this level is
                    // cancelled, not completed, and the sink must not be handed the gap.
                    drainCancelled(futures);
                    return null;
                }
                results.add(result);
            }
            for (U u : ready) done.add(dirOf.apply(u));
            remaining.removeAll(ready);
            R sinkStop = sink.after(ready, results, remaining);
            if (sinkStop != null) return sinkStop;
        }
        return null;
    }

    /**
     * Longest chain of dependents above each unit (a unit nothing depends on scores 0). Drives
     * critical-path-first admission; memoized DFS over the reversed edge map, cycle-tolerant
     * (a cycle scores 0 here — {@link #unsatisfiable} reports it when admission stalls).
     */
    public static <U> Map<Path, Integer> dependentChainHeight(
            List<U> units, Function<U, Path> dirOf, Map<Path, Set<Path>> edges, Set<Path> unitDirs) {
        Map<Path, List<Path>> dependents = new HashMap<>();
        for (U u : units) {
            Path dir = dirOf.apply(u);
            for (Path dep : edges.getOrDefault(dir, Set.of())) {
                if (unitDirs.contains(dep)) {
                    dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(dir);
                }
            }
        }
        Map<Path, Integer> memo = new HashMap<>();
        for (U u : units) heightOf(dirOf.apply(u), dependents, memo, new HashSet<>());
        return memo;
    }

    private static int heightOf(Path dir, Map<Path, List<Path>> dependents, Map<Path, Integer> memo, Set<Path> onPath) {
        Integer cached = memo.get(dir);
        if (cached != null) return cached;
        if (!onPath.add(dir)) return 0; // cycle: scored elsewhere as unsatisfiable
        int max = 0;
        for (Path d : dependents.getOrDefault(dir, List.of())) {
            max = Math.max(max, 1 + heightOf(d, dependents, memo, onPath));
        }
        onPath.remove(dir);
        memo.put(dir, max);
        return max;
    }

    /**
     * Bounded settle window for the cancel path — long enough for cooperative stoppage
     * (SessionCancel + JobWorkers kills, sub-second by contract) with headroom, short enough
     * that a wedged step can never pin cancel.
     */
    static final long CANCEL_DRAIN_MS = 2_000L;

    /**
     * Admission gate: a queued task starting after cancel must do nothing (and emit nothing). Its
     * {@code null} is the one the completion loops read as "cancelled, never ran".
     */
    private static <U, R> @Nullable R gated(
            BooleanSupplier stop, PhasedUnitTask<U, R> task, U unit, Runnable artifactsReady) {
        if (stop.getAsBoolean()) return null;
        try (LiveUnits.Lease running = LiveUnits.enter()) {
            return task.run(unit, artifactsReady);
        }
    }

    /** Fail-fast path: in-flight modules keep building; only queued-not-started are prevented. */
    private static void cancelAll(Iterable<? extends CompletableFuture<?>> futures) {
        for (CompletableFuture<?> f : futures) {
            f.cancel(true);
        }
    }

    /** Cancel path: wait (bounded) for in-flight tasks so their events precede our return. */
    private static void drainCancelled(Iterable<? extends CompletableFuture<?>> futures) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CANCEL_DRAIN_MS);
        for (CompletableFuture<?> f : futures) {
            long left = deadline - System.nanoTime();
            if (left <= 0) return; // residual: a task outliving the drain may emit late events
            try {
                f.get(left, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // failed / timed out — best-effort drain
                Log.debug("drainCancelled: failed / timed out", e);
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
