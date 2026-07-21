// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.ToLongFunction;

/**
 * Conservative cache-savings estimate: longest cold-cost path through each module's step DAG, then
 * the module DAG ({@code saved = uncached − wall}). Pure/testable; for successful builds only.
 */
public final class CacheBenefit {

    private CacheBenefit() {}

    /** One step within a module: its terminal {@code StepStatus} name, measured millis, and edges. */
    public record StepInput(String name, String status, long millis, List<String> requires) {
        public StepInput {
            requires = requires == null ? List.of() : List.copyOf(requires);
        }
    }

    /** One module's steps. {@code dir} is the canonical project dir ({@code ""} = single-pipeline). */
    public record ModuleInput(String dir, List<StepInput> steps) {
        public ModuleInput {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /**
     * The computed benefit.
     *
     * @param estimatedUncachedMillis cold-cache critical-path estimate for the whole build
     * @param savedMillis {@code max(0, estimatedUncached - actual)}
     * @param coveredSkips cache-hit steps that had a real historical baseline
     * @param totalSkips cache-hit steps in the build
     */
    public record Result(long estimatedUncachedMillis, long savedMillis, long coveredSkips, long totalSkips) {

        /** Fraction of the estimated cold build that the cache eliminated (0 when nothing to save). */
        public double pct() {
            return estimatedUncachedMillis > 0 ? (double) savedMillis / estimatedUncachedMillis : 0.0;
        }

        /** Fraction of cache hits backed by a real baseline; 1.0 when there were no cache hits. */
        public double coverage() {
            return totalSkips == 0 ? 1.0 : (double) coveredSkips / totalSkips;
        }
    }

    /**
     * Compute the benefit.
     *
     * @param modules per-module steps (with dependency edges)
     * @param moduleEdges module dir → the dirs that must build before it (may be null/empty)
     * @param actualWallClockMillis the build's real wall-clock
     * @param baseline {@code (dir, step)} → the successful-run average for a cache-hit step, or
     *     empty when no baseline exists (drives coverage; empty ⇒ that skip contributes ~0)
     */
    public static Result compute(
            List<ModuleInput> modules,
            Map<String, Set<String>> moduleEdges,
            long actualWallClockMillis,
            BiFunction<String, String, OptionalLong> baseline) {

        long coveredSkips = 0;
        long totalSkips = 0;
        Map<String, Long> moduleCost = new HashMap<>();

        for (ModuleInput m : modules) {
            Map<String, List<String>> stepPrereqs = new HashMap<>();
            Map<String, Long> stepWeight = new HashMap<>();
            List<String> stepNames = new ArrayList<>();
            for (StepInput s : m.steps()) {
                stepNames.add(s.name());
                stepPrereqs.put(s.name(), s.requires());
                long weight;
                if ("SKIPPED".equals(s.status())) {
                    totalSkips++;
                    OptionalLong base = baseline.apply(m.dir(), s.name());
                    if (base.isPresent()) {
                        coveredSkips++;
                        weight = Math.max(0, base.getAsLong());
                    } else {
                        // No history for this step: fall back to its (near-zero) restore time.
                        // Under-counts the saving, never over-counts, and lowers coverage.
                        weight = Math.max(0, s.millis());
                    }
                } else {
                    // Ran for real this build — its measured duration is the truth.
                    weight = Math.max(0, s.millis());
                }
                stepWeight.put(s.name(), weight);
            }
            long level1 = longestPath(stepNames, stepPrereqs, n -> stepWeight.getOrDefault(n, 0L));
            moduleCost.put(m.dir(), level1);
        }

        Map<String, Set<String>> edges = moduleEdges == null ? Map.of() : moduleEdges;
        long estimatedUncached =
                longestPath(new ArrayList<>(moduleCost.keySet()), edges, d -> moduleCost.getOrDefault(d, 0L));

        long saved = Math.max(0, estimatedUncached - Math.max(0, actualWallClockMillis));
        return new Result(estimatedUncached, saved, coveredSkips, totalSkips);
    }

    /**
     * Longest weighted path through a DAG. {@code prereqs} maps a node to the nodes that must
     * precede it; edges pointing outside {@code nodes} are ignored (as {@code maxReadyWidth} does).
     * Returns the maximum over all nodes of {@code weight(node) + longest path into node}.
     */
    private static <T> long longestPath(
            Collection<T> nodes, Map<T, ? extends Collection<T>> prereqs, ToLongFunction<T> weight) {
        Set<T> nodeSet = new HashSet<>(nodes);
        Map<T, Long> memo = new HashMap<>();
        long best = 0;
        for (T n : nodeSet) {
            best = Math.max(best, pathTo(n, nodeSet, prereqs, weight, memo, new HashSet<>()));
        }
        return best;
    }

    private static <T> long pathTo(
            T node,
            Set<T> nodeSet,
            Map<T, ? extends Collection<T>> prereqs,
            ToLongFunction<T> weight,
            Map<T, Long> memo,
            Set<T> onStack) {
        Long done = memo.get(node);
        if (done != null) return done;
        if (!onStack.add(node)) return 0; // defensive: a cycle would otherwise recurse forever
        long maxInto = 0;
        Collection<T> pres = prereqs.get(node);
        if (pres != null) {
            for (T pre : pres) {
                if (nodeSet.contains(pre)) {
                    maxInto = Math.max(maxInto, pathTo(pre, nodeSet, prereqs, weight, memo, onStack));
                }
            }
        }
        onStack.remove(node);
        long total = Math.max(0, weight.applyAsLong(node)) + maxInto;
        memo.put(node, total);
        return total;
    }
}
