// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.wire.runtime.ExplainPlan;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * One {@code jk explain} answer for every surface: the forecast plan plus both ETAs — the
 * incremental estimate and the same graph priced as a full rebuild. The wire verb streams the
 * plan's module/step/edge burst; MCP encodes a budgeted summary. Both compute here.
 */
public record ExplainReport(ExplainPlan plan, long etaMillis, long fullMillis) {

    /**
     * ETA inputs the session does not carry; {@link #defaults()} is the bare {@code jk explain}
     * shape. Everything else the estimate needs (workers, JDK root, verbosity, test parallelism)
     * is read off the session so it cannot differ from the build's.
     */
    public record Knobs(@Nullable String profile, int maxModuleConcurrency, boolean skipTests) {

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
        ExplainPlan plan =
                SessionContext.where(session, () -> BuildService.explain(entryDir, build, cache, k.skipTests()));
        if (plan.hasErrors()) {
            return new ExplainReport(plan, 0L, 0L);
        }
        long eta = SessionContext.where(
                session,
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
        return new ExplainReport(plan, eta, full);
    }
}
