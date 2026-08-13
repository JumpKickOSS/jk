// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.BufferedWriter;
import java.net.URI;
import java.nio.file.Path;

public final class AuditVerb implements HostedVerb {

    private final VerbHost host;

    public AuditVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.AUDIT_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("audit");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-audit-";
    }

    @Override
    public void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                Path entryDir = Path.of(Jsonl.str(requestLine, "dir"));
                Path cache = Path.of(Jsonl.str(requestLine, "cache"));
                String severity = Jsonl.str(requestLine, "severity");
                String batch = Jsonl.str(requestLine, "osvBatchUrl");
                String vulns = Jsonl.str(requestLine, "osvVulnsUrl");
                Session session = Session.defaults()
                        .withWorkingDir(entryDir)
                        .withCacheDir(cache)
                        .withCancel(cancelToken)
                        .withJvm(ProtoSession.jvmTuning(requestLine));
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                cc.jumpkick.run.BuildPlan plan = cc.jumpkick.runtime.AuditPlans.auditBuildPlan(
                        cc.jumpkick.lock.LockPaths.lockFile(entryDir),
                        cache,
                        severity,
                        batch != null ? URI.create(batch) : null,
                        vulns != null ? URI.create(vulns) : null,
                        (module, version, vulnId, sev, summary) -> host.sendQuiet(
                                writer, ProtoEvents.auditFinding(dir, module, version, vulnId, sev, summary)));
                host.streamSinglePlan(plan, session, writer, result -> ProtoEvents.planFinish(dir, result.success()));
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
            }

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
    }
}
