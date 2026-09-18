// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.BuildBlock;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.base.AuditPlans;
import cc.jumpkick.wire.protocol.AuditRequest;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProtoSession;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            try {
                AuditRequest body = AuditRequest.decode(requestLine);
                Path entryDir = Path.of(body.dir());
                Path cache = Path.of(body.cache());
                Session session = ProtoSession.sessionOf(requestLine, cancelToken);
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                List<BuildBlock.AuditIgnore> ignores = auditIgnores(entryDir);
                LocalDate today = LocalDate.ofInstant(Clock.SYSTEM.instant(), ZoneId.systemDefault());
                BuildPlan plan = AuditPlans.auditBuildPlan(
                        LockPaths.lockFile(entryDir),
                        cache,
                        body.severity(),
                        body.osvBatchUrl() != null ? URI.create(body.osvBatchUrl()) : null,
                        body.osvVulnsUrl() != null ? URI.create(body.osvVulnsUrl()) : null,
                        finding ->
                                host.sendQuiet(writer, ProtoEvents.auditFinding(dir, finding.under(ignores, today))));
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

    /**
     * The {@code [audit] ignore} list of the manifest beside the lock — the workspace root's, or
     * the standalone project's. None when that manifest is absent (a bare lock still audits).
     */
    private static List<BuildBlock.AuditIgnore> auditIgnores(Path entryDir) throws IOException {
        Path manifest = ManifestPaths.manifestIn(LockPaths.lockOwnerDir(entryDir));
        if (!Files.isRegularFile(manifest)) return List.of();
        return JkBuildParser.parse(manifest).build().auditIgnores();
    }
}
