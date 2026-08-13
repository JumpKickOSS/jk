// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.engine.listen.BridgingPlanListener;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.Task;
import java.io.BufferedWriter;
import java.util.function.Function;

/** SINGLE_PLAN_DIR-tagged plan-step burst shared by test / single-build. */
final class PlanBurst {

    private PlanBurst() {}

    static void announce(VerbHost host, BuildPlan plan, BufferedWriter writer) {
        String dir = EngineProtocol.SINGLE_PLAN_DIR;
        for (Task p : plan.steps()) {
            host.sendQuiet(
                    writer,
                    EngineProtocol.planStep(
                            dir,
                            p.name(),
                            p.label(),
                            BridgingPlanListener.phaseWire(p.group().orElse(null))));
        }
        host.sendQuiet(writer, EngineProtocol.planDone(1));
        plan.addListener(host.planListener(dir, writer, plan));
    }

    static void stream(
            VerbHost host,
            BuildPlan plan,
            cc.jumpkick.config.Session session,
            BufferedWriter writer,
            Function<cc.jumpkick.run.BuildPlanResult, String> finishEncoder)
            throws Exception {
        String dir = EngineProtocol.SINGLE_PLAN_DIR;
        for (Task p : plan.steps()) {
            host.sendQuiet(
                    writer,
                    EngineProtocol.planStep(
                            dir,
                            p.name(),
                            p.label(),
                            BridgingPlanListener.phaseWire(p.group().orElse(null))));
        }
        host.sendQuiet(writer, EngineProtocol.planDone(1));
        plan.addListener(host.planListener(dir, writer, finishEncoder));
        cc.jumpkick.config.SessionContext.where(session, plan::run);
    }
}
