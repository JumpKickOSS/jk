// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import cc.jumpkick.host.time.Clock;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
 *
 * <p>A model seeded with each module's steps ({@link #seedSteps}) is priced in ms at {@link
 * WorkModel#MS_PER_WEIGHT}, with no scale from a seed it would have to match, and counts a module
 * down step by step: a finished step leaves nothing, one not yet started its whole price, a running
 * one its price less its elapsed time (held at {@link #OVERRUN_FLOOR} of it once it overruns), and
 * a running suite the share of its class walls still to run, following its own rate once {@link
 * #RATE_FROM} of it is done. The plan's tick fraction is not read for such a module: its ticks are
 * spread evenly over steps of very different length.
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

    /** A running step past its price keeps this share of it as its remainder. */
    public static final double OVERRUN_FLOOR = 0.05;

    /** The share of a suite's class walls after which its remainder follows its observed rate. */
    public static final double RATE_FROM = 0.25;

    /** Per module, the steps it is priced to run; empty for a tick-fraction model. */
    private final Map<Path, List<StepCost>> steps = new HashMap<>();
    /** Per module, when each step started (ms). */
    private final Map<Path, Map<String, Long>> started = new HashMap<>();
    /** Per module, the steps that finished. */
    private final Map<Path, Set<String>> finished = new HashMap<>();
    /** Per module, its running suite's share of class walls done (0..1). */
    private final Map<Path, Double> suiteDone = new HashMap<>();

    private Clock clock = Clock.SYSTEM;

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
            List<ModuleWorkCost> costs, long R0ms, int concurrency, boolean serial, boolean parallelTests) {
        Objects.requireNonNull(costs, "costs");
        long ideal0 = WorkSchedule.schedule(costs, concurrency, serial, parallelTests);
        long r0 = Math.max(0, R0ms);
        if (ideal0 <= 0) {
            if (r0 == 0) {
                // Nothing modeled — empty remaining forever.
                return new RemainingWork(0, 1.0, concurrency, serial, parallelTests, List.of());
            }
            // Costs empty/zero but R0 > 0 (history-only seed): synthesize unit weights so
            // remaining() reports R0 and drains as modules complete, instead of filtering every
            // zero-weight cost and pegging the bar at 99% while the countdown still shows R0.
            // With no module list at all, one synthetic blob holds R0 until the build finishes.
            List<ModuleWorkCost> synth = new ArrayList<>();
            for (ModuleWorkCost c : costs) {
                if (c == null || c.dir() == null) continue;
                synth.add(new ModuleWorkCost(c.dir(), c.prereqs(), Math.max(1, c.weight()), c.testWeight()));
            }
            if (synth.isEmpty()) {
                // Sentinel dir — must be a legal Path on every OS (Windows rejects `<`/`>`).
                synth.add(new ModuleWorkCost(Path.of("__history-seed__"), null, 1, 0));
            }
            long idealSynth = WorkSchedule.schedule(synth, concurrency, serial, parallelTests);
            double w2 = idealSynth > 0 ? (double) r0 / (double) idealSynth : r0;
            return new RemainingWork(r0, w2, concurrency, serial, parallelTests, synth);
        }
        double w2ms = (double) r0 / (double) ideal0;
        return new RemainingWork(r0, w2ms, concurrency, serial, parallelTests, costs);
    }

    /**
     * Seed from each module's steps: every module costs the sum of its step prices in weight units
     * ({@code prefix + max(suite, tail)} via {@link WorkSchedule}), and {@link #R0()} is that
     * schedule in ms at {@link WorkModel#MS_PER_WEIGHT}. {@code costs} supplies the graph (prereqs),
     * gates and suite shapes; a module with no steps keeps its cost and its tick fraction.
     */
    public static RemainingWork seedSteps(
            List<ModuleWorkCost> costs,
            Map<Path, List<StepCost>> steps,
            int concurrency,
            boolean serial,
            boolean parallelTests) {
        Objects.requireNonNull(costs, "costs");
        long ideal = WorkSchedule.schedule(costs, concurrency, serial, parallelTests);
        RemainingWork rw = new RemainingWork(
                ideal * WorkModel.MS_PER_WEIGHT, WorkModel.MS_PER_WEIGHT, concurrency, serial, parallelTests, costs);
        if (steps != null) {
            for (var e : steps.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && rw.pending.containsKey(e.getKey())) {
                    rw.steps.put(e.getKey(), List.copyOf(e.getValue()));
                }
            }
        }
        return rw;
    }

    /** Test seam: the clock running steps count down against. */
    public synchronized RemainingWork withClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        return this;
    }

    /** {@code step} of the module at {@code dir} started now. */
    public synchronized void stepStart(Path dir, String step) {
        if (dir == null || step == null || !steps.containsKey(dir)) return;
        started.computeIfAbsent(dir, k -> new HashMap<>()).putIfAbsent(step, clock.millis());
    }

    /** {@code step} of the module at {@code dir} finished. */
    public synchronized void stepFinish(Path dir, String step) {
        if (dir == null || step == null || !steps.containsKey(dir)) return;
        finished.computeIfAbsent(dir, k -> new HashSet<>()).add(step);
    }

    /** The running suite of the module at {@code dir} has run {@code fracDone} of its class walls. */
    public synchronized void suiteProgress(Path dir, double fracDone) {
        if (dir == null || !steps.containsKey(dir)) return;
        suiteDone.put(dir, clamp01(fracDone));
    }

    /** Whether the module at {@code dir} is counted down by its steps. */
    public synchronized boolean steps(Path dir) {
        return steps.containsKey(dir);
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
        long now = steps.isEmpty() ? 0 : clock.millis();
        for (Map.Entry<Path, ModuleWorkCost> e : pending.entrySet()) {
            List<StepCost> own = steps.get(e.getKey());
            ModuleWorkCost r = own != null
                    ? stepResidual(e.getValue(), own, now)
                    : e.getValue().residual(doneFrac.getOrDefault(e.getKey(), 0.0));
            if (r.weight() > 0 || r.testWeight() > 0) residual.add(r);
        }
        if (residual.isEmpty()) return 0;
        long idealWeights = WorkSchedule.schedule(residual, concurrency, serial, parallelTests);
        return Math.max(0, Math.round(idealWeights * weightToMs));
    }

    /** The module's cost still to run, step by step (see the class comment). */
    private ModuleWorkCost stepResidual(ModuleWorkCost c, List<StepCost> own, long now) {
        Path dir = c.dir();
        Set<String> done = finished.getOrDefault(dir, Set.of());
        Map<String, Long> starts = started.getOrDefault(dir, Map.of());
        double pre = 0, test = 0, tail = 0, prePriced = 0, testPriced = 0;
        for (StepCost s : own) {
            double w = s.weight();
            if (s.kind() == StepCost.Kind.PRE) prePriced += w;
            if (s.kind() == StepCost.Kind.TEST) testPriced += w;
            double left;
            if (done.contains(s.step())) left = 0;
            else if (!starts.containsKey(s.step())) left = w;
            else {
                double elapsed = Math.max(0, now - starts.get(s.step())) / (double) WorkModel.MS_PER_WEIGHT;
                Double f = s.kind() == StepCost.Kind.TEST ? suiteDone.get(dir) : null;
                if (f != null && f > 0) {
                    left = f >= RATE_FROM ? elapsed * (1 - f) / f : w * (1 - f);
                    left = Math.max(f >= 1.0 ? 0 : OVERRUN_FLOOR * w * (1 - f), left);
                } else {
                    left = Math.max(OVERRUN_FLOOR * w, w - elapsed);
                }
            }
            switch (s.kind()) {
                case PRE -> pre += left;
                case TEST -> test += left;
                case TAIL -> tail = Math.max(tail, left);
            }
        }
        int gate = c.gateWeight() == ModuleWorkCost.UNKNOWN_GATE
                ? ModuleWorkCost.UNKNOWN_GATE
                : (int) Math.round(Math.min(c.gateWeight(), pre));
        int suiteWall1 = testPriced > 0 ? (int) Math.round(c.suiteWall1() * test / testPriced) : 0;
        return new ModuleWorkCost(
                dir,
                c.prereqs(),
                (int) Math.round(pre + test + tail),
                (int) Math.round(test),
                (int) Math.round(tail),
                gate,
                suiteWall1,
                c.suiteClasses());
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}
