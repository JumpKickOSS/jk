// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.host.Log;
import cc.jumpkick.runtime.BuildGraph;
import cc.jumpkick.runtime.Calibration;
import cc.jumpkick.runtime.EffortWeights;
import cc.jumpkick.wire.runtime.ExplainPlan;
import cc.jumpkick.wire.runtime.ModuleWorkCost;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The build's estimate for {@link BuildService}: priced step by step from the project's step ledger
 * ({@link StepEta}). Explain and live build must share this path bit-for-bit.
 */
@NullMarked
public final class BuildEta {

    private BuildEta() {}

    /**
     * Predicted wall-clock for building {@code plan}, in millis ({@code 0} = nothing to do / fully
     * cached).
     *
     * <p><b>Hard invariant:</b> this is the <em>only</em> ETA seed used by both {@code jk explain}
     * and {@code jk build}'s countdown. Same forecast plan, same {@code workers}/{@code
     * maxModuleConcurrency}/{@code parallelTests}, same {@link StepEta#model} — the numbers must
     * match bit-for-bit for a given workspace and session. See {@code
     * docs/contributors/progress-contract.md}.
     *
     * @param workers within-module test JVMs; {@code 0} = auto (identical to bare {@code jk build})
     * @param maxModuleConcurrency module-concurrency cap from {@code -j} / jobs (same as workspace
     *     build); {@code ≤ 0} means clamp only to available processors
     */
    public static long estimateEtaMillis(
            ExplainPlan plan,
            Path entryDir,
            Path cache,
            int workers,
            @Nullable Path jdksDir,
            @Nullable String profile,
            boolean skipTests,
            boolean verbose,
            boolean parallelTests,
            int maxModuleConcurrency) {
        return estimateEtaModel(
                        plan,
                        entryDir,
                        cache,
                        workers,
                        jdksDir,
                        profile,
                        skipTests,
                        verbose,
                        parallelTests,
                        maxModuleConcurrency)
                .etaMs();
    }

    public static BuildService.EtaModel estimateEtaModel(
            ExplainPlan plan,
            Path entryDir,
            Path cache,
            int workers,
            @Nullable Path jdksDir,
            @Nullable String profile,
            boolean skipTests,
            boolean verbose,
            boolean parallelTests,
            int maxModuleConcurrency) {
        return estimateEtaModel(
                plan,
                entryDir,
                cache,
                workers,
                jdksDir,
                profile,
                skipTests,
                verbose,
                parallelTests,
                maxModuleConcurrency,
                null);
    }

    /** As above; a re-pricing passes the build's {@code seed}, whose work set and shape it keeps. */
    public static BuildService.EtaModel estimateEtaModel(
            ExplainPlan plan,
            Path entryDir,
            Path cache,
            int workers,
            @Nullable Path jdksDir,
            @Nullable String profile,
            boolean skipTests,
            boolean verbose,
            boolean parallelTests,
            int maxModuleConcurrency,
            BuildService.@Nullable EtaModel seed) {
        try {
            // Host calibration: cheap when present; bootstrap probe once when missing (network
            // unless --offline). Host scale then multiplies product baselines for cold steps.
            Calibration.ensure(jdksDir);
            return StepEta.model(
                    plan,
                    entryDir,
                    cache,
                    workers,
                    jdksDir,
                    profile,
                    skipTests,
                    verbose,
                    parallelTests,
                    maxModuleConcurrency,
                    seed);
        } catch (RuntimeException e) {
            // Never fail explain/build over the estimate — but do not silently advertise 0s/empty.
            Log.warn("jk: ETA estimate failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return BuildService.EtaModel.empty();
        }
    }

    /**
     * Module-concurrency budget for the ETA schedule — the executor's own cap, so explain and the
     * live countdown clamp the same way: the request's {@code -j} when it carries one, else the
     * engine's resolved jobs (every core). Not the graph's ready width: the live scheduler admits a
     * dependent as soon as its prerequisites have compiled, so a build's in-flight count is bounded
     * by the cap and not by how many modules are ready under full-completion semantics — the
     * dogfood rebuild keeps 24 modules in flight where that width says 14.
     */
    static int etaConcurrency(int maxModuleConcurrency) {
        return StepEta.etaConcurrency(maxModuleConcurrency);
    }

    /**
     * The costs of the scheduled {@code units}, in their sequence (workspace topo / dirty list) so
     * first-ready schedule admission matches {@link WorkspaceScheduler}.
     */
    static List<ModuleWorkCost> orderCostsLikeUnits(
            List<BuildGraph.BuildUnit> units, List<EffortWeights.ModuleCost> costs) {
        Map<Path, EffortWeights.ModuleCost> byDir = new LinkedHashMap<>();
        if (costs != null) {
            for (EffortWeights.ModuleCost c : costs) {
                if (c != null && c.dir() != null) byDir.put(c.dir(), c);
            }
        }
        List<ModuleWorkCost> ordered = new ArrayList<>();
        if (units != null) {
            for (BuildGraph.BuildUnit u : units) {
                EffortWeights.ModuleCost c = byDir.remove(u.dir());
                if (c != null) {
                    ordered.add(c.toWorkCost());
                }
            }
        }
        // A module the forecast priced but the build does not schedule (a clean one: the forecast
        // covers the workspace) never starts or finishes, so it is left out: kept, it held the
        // residual up to its share of the schedule and the bar stalled two thirds of the way.
        return ordered;
    }
}
