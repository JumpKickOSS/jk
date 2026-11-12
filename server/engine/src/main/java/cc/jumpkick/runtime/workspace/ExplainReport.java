// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.EffortWeights;
import cc.jumpkick.wire.runtime.BuildKnobs;
import cc.jumpkick.wire.runtime.ExplainPlan;
import cc.jumpkick.wire.runtime.ModuleWorkCost;
import cc.jumpkick.wire.runtime.WorkSchedule;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One {@code jk explain} answer for every surface: the forecast plan plus both ETAs — the
 * incremental estimate and the same graph priced as a full rebuild. The wire verb streams the
 * plan's module/step/edge burst; MCP encodes a budgeted summary. Both compute here. {@code
 * lowMillis} and {@code highMillis} bound the incremental estimate: its likely range (JK-4TEZ7).
 */
public record ExplainReport(
        ExplainPlan plan,
        long etaMillis,
        long fullMillis,
        long lowMillis,
        long highMillis,
        /**
         * The estimate's critical path, first module first: the chain of modules, each admitted by
         * the classes of the one before, that ends with the module finishing last (JK-47ZGV).
         */
        List<Path> criticalPath) {

    public ExplainReport {
        criticalPath = criticalPath == null ? List.of() : List.copyOf(criticalPath);
    }

    public ExplainReport(ExplainPlan plan, long etaMillis, long fullMillis) {
        this(plan, etaMillis, fullMillis, etaMillis, etaMillis, List.of());
    }

    /** The bare {@code jk explain}: {@code jk build}'s knobs under {@code session}, no module budget. */
    public static ExplainReport compute(Path entryDir, JkBuild build, Path cache, Session session) throws Exception {
        return compute(entryDir, build, cache, session, BuildKnobs.of(session), 0);
    }

    /**
     * Forecast + ETAs under {@code session} and the {@code knobs} of the {@code jk build} being
     * explained; {@code maxModuleConcurrency} is its module budget, 0 for auto. Everything else the
     * estimate needs (workers, JDK root, verbosity, test parallelism) is read off the session so it
     * cannot differ from the build's. When the session already prices a rebuild ({@code
     * config.rebuild}/{@code force}), the incremental ETA <em>is</em> the full one.
     */
    public static ExplainReport compute(
            Path entryDir, JkBuild build, Path cache, Session session, BuildKnobs knobs, int maxModuleConcurrency)
            throws Exception {
        ExplainPlan plan = SessionContext.where(session, () -> BuildService.explain(entryDir, build, cache, knobs));
        if (plan.hasErrors()) {
            return new ExplainReport(plan, 0L, 0L);
        }
        BuildService.EtaModel model = SessionContext.where(
                session,
                () -> BuildService.estimateEtaModel(
                        plan,
                        entryDir,
                        cache,
                        session.requestedTestWorkers(),
                        session.jdksDir(),
                        knobs.profile(),
                        knobs.skipTests(),
                        session.verbose(),
                        session.parallelTests(),
                        maxModuleConcurrency));
        long eta = model.etaMs();
        long[] range = model.range(session.parallelTests());
        JkConfig config = session.config();
        boolean alreadyFull = config.rebuildOr(false) || config.forceOr(false);
        long full;
        if (alreadyFull) {
            full = eta;
        } else {
            Session fullSession = session.withConfig(config.withRebuild(true));
            full = SessionContext.where(
                    fullSession,
                    () -> BuildService.estimateEtaMillis(
                            plan,
                            entryDir,
                            cache,
                            session.requestedTestWorkers(),
                            session.jdksDir(),
                            knobs.profile(),
                            knobs.skipTests(),
                            session.verbose(),
                            session.parallelTests(),
                            maxModuleConcurrency));
        }
        List<ModuleWorkCost> work = new ArrayList<>();
        for (EffortWeights.ModuleCost c : model.costs()) work.add(c.toWorkCost());
        List<Path> critical = eta > 0 ? WorkSchedule.criticalPath(work, model.concurrency()) : List.of();
        return new ExplainReport(plan, eta, full, range[0], range[1], critical);
    }
}
