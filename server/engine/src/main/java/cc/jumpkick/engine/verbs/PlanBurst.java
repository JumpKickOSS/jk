// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.listen.BridgingPlanListener;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import java.io.BufferedWriter;
import java.util.function.Function;

/** SINGLE_PLAN_DIR-tagged plan-step burst, and the verdict a single-plan verb hands back. */
final class PlanBurst {

    private PlanBurst() {}

    /** The verdict and the result it derives from, for a caller that sends its own terminal. */
    record Outcome(JobOutcome outcome, BuildPlanResult result) {}

    static void announce(VerbHost host, BuildPlan plan, BufferedWriter writer) {
        announceSteps(host, plan, writer);
        plan.addListener(host.planListener(EngineProtocol.SINGLE_PLAN_DIR, writer, plan));
    }

    static JobOutcome stream(
            VerbHost host,
            BuildPlan plan,
            Session session,
            BufferedWriter writer,
            Function<BuildPlanResult, String> finishEncoder)
            throws Exception {
        announceSteps(host, plan, writer);
        plan.addListener(host.planListener(EngineProtocol.SINGLE_PLAN_DIR, writer, finishEncoder));
        return verdictOf(SessionContext.where(session, plan::run));
    }

    /**
     * As {@link #stream}, but no terminal line is emitted: the caller encodes and sends it once
     * the locks it holds are released — a client may act destructively (delete the cache root and
     * the lock file in it) the moment it reads the terminal. Progress/step events still stream
     * live from inside the run.
     */
    static Outcome streamWithoutFinish(VerbHost host, BuildPlan plan, Session session, BufferedWriter writer)
            throws Exception {
        announceSteps(host, plan, writer);
        plan.addListener(
                host.planListener(EngineProtocol.SINGLE_PLAN_DIR, writer, (Function<BuildPlanResult, String>) null));
        BuildPlanResult result = SessionContext.where(session, plan::run);
        return new Outcome(verdictOf(result), result);
    }

    private static void announceSteps(VerbHost host, BuildPlan plan, BufferedWriter writer) {
        String dir = EngineProtocol.SINGLE_PLAN_DIR;
        for (Task p : plan.steps()) {
            host.sendQuiet(
                    writer,
                    ProtoEvents.planStep(
                            dir,
                            p.name(),
                            p.label(),
                            BridgingPlanListener.phaseWire(p.group().orElse(null))));
        }
        host.sendQuiet(writer, ProtoEvents.planDone(1));
    }

    private static JobOutcome verdictOf(BuildPlanResult result) {
        if (result.userCancelled()) return JobOutcome.cancelled();
        return result.success() ? JobOutcome.ok() : JobOutcome.failed(Exit.FAILURE);
    }
}
