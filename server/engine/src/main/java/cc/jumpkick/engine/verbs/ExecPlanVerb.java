// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.host.Errors;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.runtime.ExecPlans;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ExecPlan;
import cc.jumpkick.wire.protocol.ProtoSession;
import java.io.BufferedWriter;
import java.nio.file.Path;

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
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            ExecPlan plan;
            try {
                String binDir = Jsonl.str(requestLine, "binDir");
                String libDir = Jsonl.str(requestLine, "libDir");
                // Under the request's session, not the daemon's. This verb assembles a
                // BuildPlanner.Inputs rather than a Session, so nothing installed the request's
                // toolchain selection and `jk run --jdk 21` resolved the JVM that runs the app
                // without the switch — the client was already sending it, and it was being dropped
                // here. runWhere is the scoped, self-restoring install, so a third verb
                // added later inherits this by taking its session from resolveSession too.
                Session session = host.resolveSession(requestLine, cancelToken, false);
                plan = SessionContext.where(
                        session,
                        () -> ExecPlans.execPlan(
                                Path.of(Jsonl.str(requestLine, "dir")),
                                Path.of(Jsonl.str(requestLine, "cache")),
                                Jsonl.str(requestLine, "kind"),
                                Jsonl.str(requestLine, "mainOverride"),
                                Jsonl.str(requestLine, "binName"),
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
