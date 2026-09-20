// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One module's priced work for wall-time scheduling and residual remaining-work {@code R(t)}.
 *
 * <p>{@link #weight()} is the module's total effort in the same unit used by {@link WorkSchedule}
 * (typically {@code EffortWeights} units that convert to ms via a fixed ms-per-weight rate).
 * {@link #testWeight()} is the serial {@code run-tests} slice used as a cross-module test floor.
 * {@link #tailWeight()} is the packaging tail — assembly / native-image / minified / sources —
 * which requires only the jar and so runs at the same time as the suite. The three together let
 * {@link WorkSchedule} price a module as {@code prefix + max(test, tail)} instead of the sum of its
 * steps; {@code 0} means "no tail known", which reduces to the old sum.
 *
 * <p>{@link #gateWeight()} is the part of the prefix a dependent waits on: parse, resolve, the
 * language compiles and the resource copy, which is when the live scheduler publishes the module's
 * classes tree and admits its dependents. Test compile, packaging and guards sit after that point
 * and gate nothing downstream. {@link #UNKNOWN_GATE} means the caller did not split the prefix, and
 * the schedule then gates on the whole prefix.
 */
public record ModuleWorkCost(
        Path dir, @Nullable Set<Path> prereqs, int weight, int testWeight, int tailWeight, int gateWeight) {

    /** {@link #gateWeight()} when the compile prefix was not priced apart from the rest. */
    public static final int UNKNOWN_GATE = -1;

    public ModuleWorkCost {
        Objects.requireNonNull(dir, "dir");
        prereqs = prereqs == null ? Set.of() : Set.copyOf(prereqs);
        weight = Math.max(0, weight);
        testWeight = Math.max(0, testWeight);
        tailWeight = Math.max(0, tailWeight);
        gateWeight = gateWeight < 0 ? UNKNOWN_GATE : gateWeight;
    }

    /** A cost with no known packaging tail and no known gate. */
    public ModuleWorkCost(Path dir, @Nullable Set<Path> prereqs, int weight, int testWeight) {
        this(dir, prereqs, weight, testWeight, 0, UNKNOWN_GATE);
    }

    /** A cost with a known tail and no known gate: dependents wait on the whole prefix. */
    public ModuleWorkCost(Path dir, @Nullable Set<Path> prereqs, int weight, int testWeight, int tailWeight) {
        this(dir, prereqs, weight, testWeight, tailWeight, UNKNOWN_GATE);
    }

    /** The compile prefix both branches share: everything that is neither suite nor tail. */
    public int prefix() {
        return Math.max(0, weight - testWeight - tailWeight);
    }

    /**
     * When a dependent may be admitted, from this module's start: the gate when it is known,
     * never later than the prefix.
     */
    public int artifactPoint() {
        int prefix = prefix();
        return gateWeight == UNKNOWN_GATE ? prefix : Math.min(gateWeight, prefix);
    }

    /** Residual cost after {@code fracDone} of the module's work has finished (0..1). */
    public ModuleWorkCost residual(double fracDone) {
        double left = 1.0 - clamp01(fracDone);
        return new ModuleWorkCost(
                dir,
                prereqs,
                scale(weight, left),
                scale(testWeight, left),
                scale(tailWeight, left),
                gateWeight == UNKNOWN_GATE ? UNKNOWN_GATE : scale(gateWeight, left));
    }

    private static int scale(int w, double left) {
        if (w <= 0 || left <= 0) return 0;
        if (left >= 1.0) return w;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.round(w * left)));
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}
