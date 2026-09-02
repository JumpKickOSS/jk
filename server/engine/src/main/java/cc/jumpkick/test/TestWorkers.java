// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.Jobs;
import cc.jumpkick.engine.plugin.HeapPlan;
import cc.jumpkick.engine.plugin.MemoryProbe;

/**
 * Within-module test JVM count (Mill {@code testParallelism} analogue).
 *
 * <ul>
 *   <li><b>explicit {@code -w N}</b> ({@code N ≥ 1}) — use N (capped by class count + heap)
 *   <li><b>auto {@code -w 0} / omit</b> — {@code min(jobs, classCount)}, then {@link HeapPlan}
 *       shrinks if free RAM cannot support that many 512 MiB-class worker heaps
 * </ul>
 *
 * <p>Matches Mill's {@code testSubprocessCount}: {@code min(jobs, numTests)} when parallel is on
 * and there is more than one class.
 */
public final class TestWorkers {

    private TestWorkers() {}

    /**
     * Resolve the number of test-runner JVMs for one module.
     *
     * @param requested {@code ≤ 0} = auto; {@code ≥ 1} = explicit
     * @param classCount discovered top-level test classes (0 if unknown/empty)
     * @param jobs effective {@code -j} / jobs budget (cores when default)
     */
    public static int resolve(int requested, int classCount, int jobs) {
        int classes = Math.max(0, classCount);
        int raw;
        if (requested > 0) {
            raw = classes == 0 ? requested : Math.min(requested, Math.max(1, classes));
        } else {
            raw = auto(jobs, classes);
        }
        return clampByHeap(Math.max(1, raw));
    }

    /** Mill-shaped auto: one class → 1; else {@code min(jobs, classCount)} — {@code jobs} pre-shared. */
    public static int auto(int jobs, int classCount) {
        if (classCount <= 1) return 1;
        int j = Math.max(1, jobs);
        return Math.max(1, Math.min(j, classCount));
    }

    /** Shrink toward 1 when free RAM cannot fund parallel heaps ({@link HeapPlan#MIN_PARALLEL_HEAP}). */
    public static int clampByHeap(int workers) {
        int w = Math.max(1, workers);
        if (w == 1) return 1;
        try {
            long free = MemoryProbe.probe().availableBytes();
            return Math.max(1, HeapPlan.compute(free, w).parallelism());
        } catch (RuntimeException e) {
            return w;
        }
    }

    /** Effective jobs budget for the current process (env + machine config). */
    public static int effectiveJobs() {
        return Jobs.resolve(JkEngineConfig.resolve());
    }
}
