// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Live remaining wall-work {@code R(t)} for one build: the schedule of unfinished module costs,
 * including partial residual for in-flight modules.
 *
 * <p>{@link #R0()} is the seed wall estimate (same number as {@code jk explain} / initial
 * countdown). {@link #remaining()} is recomputed as modules progress/finish. Bar and countdown
 * share this residual oracle: bar ≈ {@code elapsed/(elapsed+R)}; countdown re-anchors to {@code R}.
 *
 * <p>When the ideal schedule of the initial cost set differs from {@code R0} (history floors,
 * contention margins), {@code weightToMs} bakes that into every residual schedule so mid-run
 * remaining stays consistent with the seed:
 *
 * <pre>
 *   ideal0 = WorkSchedule.schedule(costs)           // weight units
 *   weightToMs = R0ms / ideal0                      // so ideal0 * weightToMs == R0ms
 *   remainingMs = WorkSchedule.schedule(residual) * weightToMs
 * </pre>
 */
public final class RemainingWork {

    private final int concurrency;
    private final boolean serial;
    private final boolean parallelTests;
    /** Multiplier from schedule weight units → wall ms (includes history/contention scale). */
    private final double weightToMs;
    private final long R0;

    /** Modules not yet complete — full original cost; residual applies via {@link #doneFrac}. */
    private final Map<Path, ModuleWorkCost> pending = new LinkedHashMap<>();
    /** In-flight fraction done within the module plan (0..1). */
    private final Map<Path, Double> doneFrac = new LinkedHashMap<>();

    private RemainingWork(
            long R0,
            double weightToMs,
            int concurrency,
            boolean serial,
            boolean parallelTests,
            List<ModuleWorkCost> costs) {
        this.R0 = Math.max(0, R0);
        this.weightToMs = weightToMs > 0 && Double.isFinite(weightToMs) ? weightToMs : 1.0;
        this.concurrency = concurrency;
        this.serial = serial;
        this.parallelTests = parallelTests;
        if (costs != null) {
            for (ModuleWorkCost c : costs) {
                if (c != null && c.dir() != null) pending.put(c.dir(), c);
            }
        }
    }

    /**
     * Seed from module costs (weight units) and wall-ms {@code R0ms} (history floors / margins
     * already applied). Ideal schedule of {@code costs} defines the weight→ms scale so residuals
     * track {@code R0}.
     */
    public static RemainingWork seed(
            List<ModuleWorkCost> costs,
            long R0ms,
            int concurrency,
            boolean serial,
            boolean parallelTests) {
        Objects.requireNonNull(costs, "costs");
        long ideal0 = WorkSchedule.schedule(costs, concurrency, serial, parallelTests);
        long r0 = Math.max(0, R0ms);
        if (ideal0 <= 0) {
            if (r0 == 0) {
                // Nothing modeled — empty remaining forever.
                return new RemainingWork(0, 1.0, concurrency, serial, parallelTests, List.of());
            }
            // Costs empty/zero but R0 > 0 (history-only seed): synthesize unit weights so
            // remaining() actually reports R0 and drains as modules complete. The old path kept
            // the zero-weight costs, remaining() filtered them all, and the clock bar pegged to
            // 99% from the first tick while the countdown still showed R0 (JK-1814). With no
            // module list at all, one synthetic blob holds R0 until the build finishes.
            List<ModuleWorkCost> synth = new ArrayList<>();
            for (ModuleWorkCost c : costs) {
                if (c == null || c.dir() == null) continue;
                synth.add(new ModuleWorkCost(c.dir(), c.prereqs(), Math.max(1, c.weight()), c.testWeight()));
            }
            if (synth.isEmpty()) {
                synth.add(new ModuleWorkCost(Path.of("<history-seed>"), null, 1, 0));
            }
            long idealSynth = WorkSchedule.schedule(synth, concurrency, serial, parallelTests);
            double w2 = idealSynth > 0 ? (double) r0 / (double) idealSynth : r0;
            return new RemainingWork(r0, w2, concurrency, serial, parallelTests, synth);
        }
        double w2ms = (double) r0 / (double) ideal0;
        return new RemainingWork(r0, w2ms, concurrency, serial, parallelTests, costs);
    }

    public long R0() {
        return R0;
    }

    public int concurrency() {
        return concurrency;
    }

    public boolean serial() {
        return serial;
    }

    public boolean parallelTests() {
        return parallelTests;
    }

    /** Modules still pending (not complete). */
    public synchronized int pendingModules() {
        return pending.size();
    }

    /**
     * Update in-flight progress for a module. {@code fracDone} is that module's BuildPlan
     * {@code numerator/denominator} (0..1).
     */
    public synchronized void moduleProgress(Path dir, double fracDone) {
        if (dir == null || !pending.containsKey(dir)) return;
        doneFrac.put(dir, clamp01(fracDone));
    }

    /** Module finished — drop from the residual schedule. */
    public synchronized void moduleComplete(Path dir) {
        if (dir == null) return;
        pending.remove(dir);
        doneFrac.remove(dir);
    }

    /** Current remaining wall ms {@code R(t)}. */
    public synchronized long remaining() {
        if (R0 <= 0) return 0;
        if (pending.isEmpty()) return 0;
        List<ModuleWorkCost> residual = new ArrayList<>(pending.size());
        for (Map.Entry<Path, ModuleWorkCost> e : pending.entrySet()) {
            double done = doneFrac.getOrDefault(e.getKey(), 0.0);
            ModuleWorkCost r = e.getValue().residual(done);
            if (r.weight() > 0 || r.testWeight() > 0) residual.add(r);
        }
        if (residual.isEmpty()) return 0;
        long idealWeights = WorkSchedule.schedule(residual, concurrency, serial, parallelTests);
        return Math.max(0, Math.round(idealWeights * weightToMs));
    }

    /**
     * Fraction of seed work completed: {@code 1 - R/R0}, clamped to {@code [0, 1]}. When
     * {@code R0 == 0}, returns {@code 1} (nothing to do).
     */
    public synchronized double completeFraction() {
        if (R0 <= 0) return 1.0;
        long r = remaining();
        if (r >= R0) return 0.0;
        return clamp01(1.0 - (double) r / (double) R0);
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}
