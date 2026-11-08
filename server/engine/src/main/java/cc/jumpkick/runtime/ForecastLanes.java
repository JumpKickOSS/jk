// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.run.JkThreads;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Visits every module of a graph once, each only after all of its in-graph prereqs were visited,
 * on up to {@code lanes} threads of {@link JkThreads#cpu()}.
 *
 * <p>A module starts the moment its own prereqs are done, not when the slowest module of a wave
 * is: walked in waves, the forecast of a workspace with a 21-module chain paid one barrier per link,
 * each waiting on that wave's slowest member, and checked 212 modules in 3.6 s with three lanes busy
 * on average. The ready queue serves the module with the longest chain under it first, so the chain
 * that decides the wall is never queued behind leaves.
 *
 * <p>What a visit publishes (its verdict, its restore ledger entries, whether its consumers see it
 * as changed) is published before any consumer is released, through the countdown that releases
 * it, so a consumer reads every prereq's result complete. A module a cycle leaves unreachable is
 * not visited; the caller covers what came back missing.
 */
@NullMarked
final class ForecastLanes {

    private ForecastLanes() {}

    /**
     * Visit {@code units} in dependency order by {@code order} (dir → prereq dirs). {@code bind}
     * carries the caller's scoped values onto a lane; a single lane runs on the caller. The first
     * failure stops the walk (modules being visited finish, none start) and is re-raised here.
     */
    static void walk(
            List<BuildGraph.BuildUnit> units,
            Map<Path, Set<Path>> order,
            int lanes,
            UnaryOperator<Callable<@Nullable Void>> bind,
            Consumer<BuildGraph.BuildUnit> visit) {
        Graph g = Graph.of(units, order);
        if (lanes <= 1) {
            walkSerial(g, visit);
            return;
        }
        new Parallel(g, visit).run(lanes, bind);
    }

    /** Kahn's order on the caller, the longest chain first. */
    private static void walkSerial(Graph g, Consumer<BuildGraph.BuildUnit> visit) {
        Map<Path, Integer> waiting = new HashMap<>();
        g.waiting.forEach((d, n) -> waiting.put(d, n.get()));
        PriorityQueue<Item> ready = new PriorityQueue<>(Item.ORDER);
        for (Item it : g.roots()) ready.add(it);
        while (!ready.isEmpty()) {
            Path dir = ready.poll().dir();
            visit.accept(g.byDir.get(dir));
            for (Path d : g.dependents.getOrDefault(dir, List.of())) {
                int left = waiting.merge(d, -1, Integer::sum);
                if (left == 0) ready.add(g.item(d));
            }
        }
    }

    /** One walk across lanes: a shared ready queue, a countdown per module, a poison per lane at the end. */
    private static final class Parallel {
        private final Graph g;
        private final Consumer<BuildGraph.BuildUnit> visit;
        private final PriorityBlockingQueue<Item> ready = new PriorityBlockingQueue<>(64, Item.ORDER);
        private final AtomicInteger open = new AtomicInteger();
        private final AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        private int lanes;

        Parallel(Graph g, Consumer<BuildGraph.BuildUnit> visit) {
            this.g = g;
            this.visit = visit;
        }

        void run(int laneCount, UnaryOperator<Callable<@Nullable Void>> bind) {
            List<Item> roots = g.roots();
            if (roots.isEmpty()) return;
            this.lanes = laneCount;
            open.set(roots.size());
            ready.addAll(roots);
            List<Future<?>> pending = new ArrayList<>(laneCount);
            for (int i = 0; i < laneCount; i++) pending.add(JkThreads.cpu().submit(bind.apply(this::lane)));
            TaskForecaster.joinWave(pending);
            Throwable failed = failure.get();
            if (failed instanceof Error error) throw error;
            if (failed instanceof RuntimeException runtime) throw runtime;
            if (failed != null) throw new IllegalStateException(failed);
        }

        private @Nullable Void lane() {
            while (true) {
                Item it;
                try {
                    it = ready.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stop(new CancellationException("forecast interrupted"));
                    return null;
                }
                if (it.dir() == null) return null;
                try {
                    visit.accept(g.byDir.get(it.dir()));
                } catch (Throwable t) {
                    stop(t);
                    return null;
                }
                for (Path d : g.dependents.getOrDefault(it.dir(), List.of())) {
                    if (Objects.requireNonNull(g.waiting.get(d)).decrementAndGet() == 0) {
                        open.incrementAndGet();
                        ready.add(g.item(d));
                    }
                }
                if (open.decrementAndGet() == 0) poison();
            }
        }

        private void stop(Throwable t) {
            failure.compareAndSet(null, t);
            poison();
        }

        /** Poison sorts first, so a stopped walk's lanes leave before taking more work. */
        private void poison() {
            for (int i = 0; i < lanes; i++) ready.add(Item.POISON);
        }
    }

    /** The in-graph prereq counts, the reverse edges and each module's chain height. */
    private record Graph(
            Map<Path, BuildGraph.BuildUnit> byDir,
            Map<Path, AtomicInteger> waiting,
            Map<Path, List<Path>> dependents,
            Map<Path, Integer> height,
            Map<Path, Integer> seq) {

        static Graph of(List<BuildGraph.BuildUnit> units, Map<Path, Set<Path>> order) {
            Map<Path, BuildGraph.BuildUnit> byDir = new LinkedHashMap<>();
            Map<Path, Integer> seq = new HashMap<>();
            for (BuildGraph.BuildUnit u : units) {
                if (byDir.putIfAbsent(u.dir(), u) == null) seq.put(u.dir(), seq.size());
            }
            Map<Path, AtomicInteger> waiting = new HashMap<>();
            Map<Path, List<Path>> dependents = new HashMap<>();
            for (Path dir : byDir.keySet()) {
                int n = 0;
                for (Path prereq : order.getOrDefault(dir, Set.of())) {
                    if (prereq.equals(dir) || !byDir.containsKey(prereq)) continue;
                    n++;
                    dependents.computeIfAbsent(prereq, k -> new ArrayList<>()).add(dir);
                }
                waiting.put(dir, new AtomicInteger(n));
            }
            Map<Path, Integer> height = new HashMap<>();
            for (Path dir : byDir.keySet()) height(dir, dependents, height, new HashMap<>());
            return new Graph(byDir, waiting, dependents, height, seq);
        }

        /** The longest dependent chain above {@code dir}, itself counted; a cycle counts once. */
        private static int height(
                Path dir, Map<Path, List<Path>> dependents, Map<Path, Integer> height, Map<Path, Boolean> onPath) {
            Integer known = height.get(dir);
            if (known != null) return known;
            if (onPath.putIfAbsent(dir, Boolean.TRUE) != null) return 0;
            int max = 0;
            for (Path d : dependents.getOrDefault(dir, List.of()))
                max = Math.max(max, height(d, dependents, height, onPath));
            onPath.remove(dir);
            height.put(dir, max + 1);
            return max + 1;
        }

        List<Item> roots() {
            List<Item> out = new ArrayList<>();
            for (Path dir : byDir.keySet())
                if (Objects.requireNonNull(waiting.get(dir)).get() == 0) out.add(item(dir));
            return out;
        }

        Item item(Path dir) {
            return new Item(dir, height.getOrDefault(dir, 1), seq.getOrDefault(dir, 0));
        }
    }

    /** A ready module: the longest chain first, then graph order. A null dir is a lane's poison. */
    private record Item(@Nullable Path dir, int height, int seq) {
        static final Item POISON = new Item(null, Integer.MAX_VALUE, -1);
        static final Comparator<Item> ORDER =
                Comparator.comparingInt((Item i) -> -i.height()).thenComparingInt(Item::seq);
    }
}
