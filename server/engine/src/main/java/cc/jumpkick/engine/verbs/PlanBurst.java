// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.listen.BridgingPlanListener;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoEvents;
import java.io.BufferedWriter;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** SINGLE_PLAN_DIR-tagged plan-step burst, and the verdict a single-plan verb hands back. */
final class PlanBurst {

    private PlanBurst() {}

    /** The verdict and the result it derives from, for a caller that sends its own terminal. */
    record Outcome(JobOutcome outcome, BuildPlanResult result) {}

    static void announce(VerbHost host, BuildPlan plan, @Nullable BufferedWriter writer) {
        announceSteps(host, plan, writer);
        plan.addListener(host.planListener(EngineProtocol.SINGLE_PLAN_DIR, writer, plan));
    }

    static JobOutcome stream(
            VerbHost host,
            BuildPlan plan,
            Session session,
            @Nullable BufferedWriter writer,
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
    static Outcome streamWithoutFinish(VerbHost host, BuildPlan plan, Session session, @Nullable BufferedWriter writer)
            throws Exception {
        announceSteps(host, plan, writer);
        plan.addListener(
                host.planListener(EngineProtocol.SINGLE_PLAN_DIR, writer, (Function<BuildPlanResult, String>) null));
        BuildPlanResult result = SessionContext.where(session, plan::run);
        return new Outcome(verdictOf(result), result);
    }

    private static void announceSteps(VerbHost host, BuildPlan plan, @Nullable BufferedWriter writer) {
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

    /**
     * A plan's verdict, narrowed by the verdict of a tool the plan drove.
     *
     * <p>A plan verdict answers one question: did the run reach the end? A few verbs drive a tool
     * whose refusal is published as a plan <em>result</em> rather than a step failure — the
     * importer's exit code, the formatter's per-file error count — because that distinction is
     * load-bearing elsewhere (it is how a dead worker is told from a file it could not format). A
     * job that ran to the end with a tool that refused is still not a green job, and
     * {@code JobEnvelope} stamps a body's verdict and nothing else, so folding the two here is the
     * verb's only chance to say so.
     *
     * @param toolExit the tool's refusal code, or {@code 0} when it was satisfied
     */
    static JobOutcome withToolExit(JobOutcome planVerdict, int toolExit) {
        if (toolExit == 0) return planVerdict;
        // A plan that already failed keeps its own code: a dead worker is not re-diagnosed as its
        // tool's complaint, and a cancelled run is not a failed one.
        return planVerdict instanceof JobOutcome.Succeeded ? JobOutcome.failed(toolExit) : planVerdict;
    }
}
