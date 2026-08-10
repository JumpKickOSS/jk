// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * One module's priced work for wall-time scheduling and residual remaining-work {@code R(t)}.
 *
 * <p>{@link #weight()} is the module's total effort in the same unit used by {@link WorkSchedule}
 * (typically {@code EffortWeights} units that convert to ms via a fixed ms-per-weight rate).
 * {@link #testWeight()} is the serial {@code run-tests} slice used as a cross-module test floor.
 */
public record ModuleWorkCost(Path dir, Set<Path> prereqs, int weight, int testWeight) {

    public ModuleWorkCost {
        Objects.requireNonNull(dir, "dir");
        prereqs = prereqs == null ? Set.of() : Set.copyOf(prereqs);
        weight = Math.max(0, weight);
        testWeight = Math.max(0, testWeight);
    }

    /** Residual cost after {@code fracDone} of the module's work has finished (0..1). */
    public ModuleWorkCost residual(double fracDone) {
        double left = 1.0 - clamp01(fracDone);
        return new ModuleWorkCost(
                dir,
                prereqs,
                scale(weight, left),
                scale(testWeight, left));
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
