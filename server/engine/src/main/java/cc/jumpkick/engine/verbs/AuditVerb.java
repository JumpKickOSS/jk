// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.AuditPlans;
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
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                Path entryDir = Path.of(Jsonl.str(requestLine, "dir"));
                Path cache = Path.of(Jsonl.str(requestLine, "cache"));
                String severity = Jsonl.str(requestLine, "severity");
                String batch = Jsonl.str(requestLine, "osvBatchUrl");
                String vulns = Jsonl.str(requestLine, "osvVulnsUrl");
                Session session = Session.defaults()
                        .withConfig(JkConfig.empty().withOffline(Jsonl.bool(requestLine, "offline", false)))
                        .withWorkingDir(entryDir)
                        .withCacheDir(cache)
                        .withCancel(cancelToken)
                        .withJvm(ProtoSession.jvmTuning(requestLine));
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                BuildPlan plan = AuditPlans.auditBuildPlan(
                        LockPaths.lockFile(entryDir),
                        cache,
                        severity,
                        batch != null ? URI.create(batch) : null,
                        vulns != null ? URI.create(vulns) : null,
                        (module, version, vulnId, sev, summary) -> host.sendQuiet(
                                writer, ProtoEvents.auditFinding(dir, module, version, vulnId, sev, summary)));
                return host.streamSinglePlan(
                        plan, session, writer, result -> ProtoEvents.planFinish(dir, result.success()));
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
                return JobOutcome.failed(Exit.FAILURE);
            }
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
            return JobOutcome.failed(Exit.FAILURE);
        }
    }
}
