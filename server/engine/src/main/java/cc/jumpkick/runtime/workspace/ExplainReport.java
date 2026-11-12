// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.EffortWeights;
import cc.jumpkick.wire.runtime.ExplainPlan;
import cc.jumpkick.wire.runtime.ModuleWorkCost;
import cc.jumpkick.wire.runtime.WorkSchedule;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

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

    /**
     * ETA inputs the session does not carry; {@link #defaults()} is the bare {@code jk explain}
     * shape. Everything else the estimate needs (workers, JDK root, verbosity, test parallelism)
     * is read off the session so it cannot differ from the build's.
     */
    public record Knobs(
            @Nullable String profile,
            int maxModuleConcurrency,
            boolean skipTests,
            /**
             * The module dirs a {@code -m}/{@code --affected-since} selector resolved to; the plan
             * prices their cone — selection plus prerequisites — as {@code jk build} with the same
             * selector schedules it. Empty: the whole workspace.
             */
            Set<Path> selection) {

        public Knobs {
            selection = selection == null ? Set.of() : Set.copyOf(selection);
        }

        public Knobs(@Nullable String profile, int maxModuleConcurrency, boolean skipTests) {
            this(profile, maxModuleConcurrency, skipTests, Set.of());
        }

        public static Knobs defaults() {
            return new Knobs(null, 0, false);
        }
    }

    /**
     * Forecast + ETAs under {@code session}. When the session already prices a rebuild
     * ({@code config.rebuild}/{@code force}), the incremental ETA <em>is</em> the full one.
     */
    public static ExplainReport compute(Path entryDir, JkBuild build, Path cache, Session session, Knobs k)
            throws Exception {
        ExplainPlan plan = SessionContext.where(
                session, () -> BuildService.explain(entryDir, build, cache, k.skipTests(), k.selection()));
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
                        k.profile(),
                        k.skipTests(),
                        session.verbose(),
                        session.parallelTests(),
                        k.maxModuleConcurrency()));
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
                            k.profile(),
                            k.skipTests(),
                            session.verbose(),
                            session.parallelTests(),
                            k.maxModuleConcurrency()));
        }
        List<ModuleWorkCost> work = new ArrayList<>();
        for (EffortWeights.ModuleCost c : model.costs()) work.add(c.toWorkCost());
        List<Path> critical = eta > 0 ? WorkSchedule.criticalPath(work, model.concurrency()) : List.of();
        return new ExplainReport(plan, eta, full, range[0], range[1], critical);
    }
}
