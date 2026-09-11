// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.host.Errors;
import cc.jumpkick.runtime.workspace.ExecPlans;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ExecPlan;
import cc.jumpkick.wire.protocol.ExecPlanRequest;
import cc.jumpkick.wire.protocol.ProtoSession;
import java.io.BufferedWriter;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

public final class ExecPlanVerb implements HostedVerb {

    private final VerbHost host;

    public ExecPlanVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.EXEC_PLAN_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("exec-plan");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-exec-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            ExecPlan plan;
            try {
                ExecPlanRequest req = ExecPlanRequest.decode(requestLine);
                String binDir = req.binDir();
                String libDir = req.libDir();
                // Under the request's session, not the daemon's: the toolchain selection the
                // app runs on is the caller's, and only an installed session carries it.
                Session session = ProtoSession.sessionOf(requestLine, cancelToken);
                plan = SessionContext.where(
                        session,
                        () -> ExecPlans.execPlan(
                                Path.of(req.dir()),
                                Path.of(req.cache()),
                                req.kind(),
                                req.mainOverride(),
                                req.binName(),
                                binDir == null ? null : Path.of(binDir),
                                libDir == null ? null : Path.of(libDir),
                                ProtoSession.variantOf(requestLine),
                                ProtoSession.clientEnvOf(requestLine)));
            } catch (Exception e) {
                plan = ExecPlan.error("unknown", Errors.text(e));
            }
            host.sendQuiet(writer, plan.encode());

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
