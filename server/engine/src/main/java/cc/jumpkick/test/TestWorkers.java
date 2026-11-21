// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.builds.AggregatedMetrics;
import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.Jobs;
import cc.jumpkick.engine.plugin.HeapPlan;
import cc.jumpkick.engine.plugin.MemoryProbe;
import cc.jumpkick.runtime.base.RecentClassWalls;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Within-module test JVM count.
 *
 * <ul>
 *   <li><b>explicit {@code -w N}</b> or a module pin ({@code N ≥ 1}) — N, capped by class count,
 *       then the heap clamp
 *   <li><b>auto</b> — {@link #autoCount}: measured class walls, capped by this module's
 *       {@link #autoShare}, then the heap clamp
 * </ul>
 */
public final class TestWorkers {

    private TestWorkers() {}

    /**
     * Resolve the number of test-runner JVMs for one module.
     *
     * @param requested {@code ≤ 0} = auto with no class-wall history; {@code ≥ 1} = explicit
     * @param classCount discovered top-level test classes ({@code 0} if unknown)
     * @param jobs for an explicit request, ignored; for auto, the {@link #autoShare} cap (the whole
     *     jobs budget when the module is alone)
     */
    public static int resolve(int requested, int classCount, int jobs) {
        int classes = Math.max(0, classCount);
        if (requested > 0) {
            int raw = classes == 0 ? requested : Math.min(requested, Math.max(1, classes));
            return clampByHeap(Math.max(1, raw));
        }
        return autoCount(jobs, Map.of(), List.of(), classes);
    }

    /**
     * Auto {@code W} for one module: {@code clamp(ceil(Σ class wall / max class wall), 1, min(share,
     * classCount))}, then {@link #clampByHeap}. A class with no recorded wall counts as the median of
     * the known ones. No history for the module is {@code min(2, share, classCount)}; a class count of
     * {@code 0} does not cap. {@code share} is {@link #autoShare}.
     *
     * @param recorded most recent wall-ms per class (FQCN); empty when the module has no history
     * @param selection classes about to run; empty means the recorded suite, padded up to
     *     {@code coldClassCount} when that is larger
     * @param coldClassCount class count to assume when nothing has been recorded, or a floor on the
     *     recorded suite when {@code selection} is empty
     */
    public static int autoCount(
            int share, @Nullable Map<String, Long> recorded, @Nullable List<String> selection, int coldClassCount) {
        return clampByHeap(uncapped(share, wallsOf(recorded, selection, coldClassCount)));
    }

    /**
     * {@link #autoCount} for {@code moduleDir}'s most recent recorded walls ({@link RecentClassWalls}).
     */
    public static int autoCount(
            int share, @Nullable Path moduleDir, @Nullable List<String> selection, int coldClassCount) {
        Map<String, Long> recorded = moduleDir == null ? Map.of() : RecentClassWalls.forModule(moduleDir);
        return autoCount(share, recorded, selection, coldClassCount);
    }

    /** {@link #autoCount} before the heap clamp. */
    static int uncapped(int share, long[] wallMs) {
        int capShare = Math.max(1, share);
        int n = wallMs == null ? 0 : wallMs.length;
        int knownCount = 0;
        for (int i = 0; i < n; i++) if (wallMs[i] > 0) knownCount++;
        if (knownCount == 0) {
            if (n <= 0) return Math.min(2, capShare);
            return Math.min(2, Math.min(capShare, n));
        }
        long[] known = new long[knownCount];
        int k = 0;
        long sum = 0;
        long max = 0;
        for (int i = 0; i < n; i++) {
            long w = wallMs[i];
            if (w <= 0) continue;
            known[k++] = w;
            sum += w;
            if (w > max) max = w;
        }
        int missing = n - knownCount;
        if (missing > 0) {
            Arrays.sort(known);
            long median = median(known);
            sum += median * missing;
            if (median > max) max = median;
        }
        int ideal = (int) Math.min(Integer.MAX_VALUE, (sum + max - 1) / max);
        int classCap = Math.min(capShare, n);
        return Math.min(Math.max(ideal, 1), Math.max(classCap, 1));
    }

    private static long[] wallsOf(
            @Nullable Map<String, Long> recorded, @Nullable List<String> selection, int coldClassCount) {
        int cold = Math.max(0, coldClassCount);
        if (selection != null && !selection.isEmpty()) {
            int n = 0;
            for (String name : selection) if (name != null && !name.isBlank()) n++;
            long[] walls = new long[n];
            int i = 0;
            for (String name : selection) {
                if (name == null || name.isBlank()) continue;
                walls[i++] = lookup(recorded, name);
            }
            return walls;
        }
        List<Long> known = new ArrayList<>();
        if (recorded != null) {
            for (Long v : recorded.values()) if (v != null && v > 0) known.add(v);
        }
        if (known.isEmpty()) return new long[cold];
        int n = Math.max(known.size(), cold);
        long[] walls = new long[n];
        for (int i = 0; i < known.size(); i++) walls[i] = known.get(i);
        return walls;
    }

    private static long lookup(@Nullable Map<String, Long> recorded, String fqcn) {
        if (recorded == null || recorded.isEmpty()) return 0;
        Long v = recorded.get(fqcn);
        if (v == null) v = recorded.get(AggregatedMetrics.sanitize(fqcn));
        return v == null || v <= 0 ? 0 : v;
    }

    /** Median of a sorted array; the mean of the two central values when the count is even. */
    private static long median(long[] sorted) {
        int n = sorted.length;
        if ((n & 1) == 1) return sorted[n / 2];
        return (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
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
     * <p>The denominator is the units actually running, which is what the plan-time share was
     * dividing by all along — the same formula, read late. Free worker leases look like
     * the same answer and are not: they are an instantaneous reading of a resource about to be
     * contended, and sizing from them let mid-build suites take sixteen runners each and starve the
     * compile lanes, which cost more at the front than the tail gained at the back.
     *
     * <p>The plan share is the floor, so this never makes a suite narrower than planned, and the jobs
     * budget the ceiling. {@code unitsRunning <= 0} means no job scope to measure, so the plan stands
     * as given — including {@code 0}, which is still "auto" and must reach the launcher as such, not
     * as a one-runner pin.
     */
    public static int liveShare(int planShare, int jobsBudget, int unitsRunning) {
        if (unitsRunning <= 0) return Math.max(0, planShare);
        int floor = Math.max(1, planShare);
        int live = Math.max(1, jobsBudget) / unitsRunning;
        return Math.clamp(live, floor, Math.max(floor, jobsBudget));
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
