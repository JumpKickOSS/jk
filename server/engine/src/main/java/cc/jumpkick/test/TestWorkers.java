// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.Jobs;
import cc.jumpkick.engine.plugin.HeapPlan;
import cc.jumpkick.engine.plugin.MemoryProbe;
import cc.jumpkick.engine.plugin.PluginSlots;

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

    /**
     * The jobs budget a build runs under: what the request carries ({@code -j} / {@code JK_JOBS} /
     * {@code [engine] jobs}, resolved client-side and sent as the module-concurrency cap), else this
     * engine's own. One source for the executor, the live countdown and {@code jk explain}.
     */
    public static int jobsBudget(int maxModuleConcurrency) {
        return maxModuleConcurrency > 0 ? maxModuleConcurrency : effectiveJobs();
    }

    /**
     * The share to actually run a suite at, sampled when it dispatches rather than when the build was
     * planned.
     *
     * <p>{@link #autoShare} divides the jobs budget by the graph's widest point, which is right while
     * that many modules contend and wrong for the last module standing: on a 20-thread host with 38
     * dirty modules the share is 1, and the tail suite runs single-file while the machine idles.
     * Raising the plan share for everyone does not fix it — a blanket {@code -w 8} makes the wide
     * phase oversubscribe and the build ends later, so the number has to be read late.
     *
     * <p>{@link PluginSlots} is the reading: it already bounds every worker fork, so its free permits
     * are what this suite could actually get right now — low while the build is wide, the whole budget
     * once the rest has drained. The plan share is the floor (this never makes a suite narrower than
     * planned) and the jobs budget the ceiling. An unbounded gate reports zero permits and answers
     * nothing, so the plan share stands.
     */
    public static int liveShare(int planShare, int jobsBudget) {
        int floor = Math.max(1, planShare);
        int free = PluginSlots.permits();
        if (free <= 0) return floor;
        return Math.clamp(free, floor, Math.max(floor, jobsBudget));
    }

    /**
     * The {@code -w 0} share: the jobs budget divided by how many modules can run at once. The
     * budget defaults to every core, so one dirty module on an idle machine still shards as wide as
     * the machine, and a wide build lands at one runner per module; {@code -j 4} caps the whole
     * build — modules and their test JVMs together — at four, which is what "concurrent
     * module/worker budget" promises. Never below one.
     */
    public static int autoShare(int jobsBudget, int width) {
        return Math.max(1, Math.max(1, jobsBudget) / Math.max(1, width));
    }
}
