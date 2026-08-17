// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.jsonl.Jsonl;
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
    public cc.jumpkick.engine.jobs.@org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            cc.jumpkick.engine.protocol.ExecPlan plan;
            try {
                String binDir = Jsonl.str(requestLine, "binDir");
                String libDir = Jsonl.str(requestLine, "libDir");
                plan = cc.jumpkick.runtime.ExecPlans.execPlan(
                        Path.of(Jsonl.str(requestLine, "dir")),
                        Path.of(Jsonl.str(requestLine, "cache")),
                        Jsonl.str(requestLine, "kind"),
                        Jsonl.str(requestLine, "mainOverride"),
                        Jsonl.str(requestLine, "binName"),
                        binDir == null ? null : Path.of(binDir),
                        libDir == null ? null : Path.of(libDir),
                        ProtoSession.variantOf(requestLine),
                        ProtoSession.clientEnvOf(requestLine));
            } catch (RuntimeException e) {
                plan = cc.jumpkick.engine.protocol.ExecPlan.error("unknown", String.valueOf(e.getMessage()));
            }
            host.sendQuiet(writer, plan.encode());

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return null;
    }
}
