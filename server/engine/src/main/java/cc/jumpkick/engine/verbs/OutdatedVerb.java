// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.host.Errors;
import cc.jumpkick.runtime.OutdatedPlans;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.OutdatedReport;
import cc.jumpkick.wire.protocol.OutdatedRequest;
import java.io.BufferedWriter;
import java.net.URI;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

public final class OutdatedVerb implements HostedVerb {

    private final VerbHost host;

    public OutdatedVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.OUTDATED_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("outdated");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-outdated-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            OutdatedReport report;
            try {
                OutdatedRequest req = OutdatedRequest.decode(requestLine);
                Path dir = Path.of(req.dir());
                Path cache = Path.of(req.cache());
                String repoUrl = req.repoUrl();
                JkConfig config = JkConfig.empty().withOffline(req.offline()).withForce(req.force());
                Session session = Session.defaults()
                        .withConfig(config)
                        .withWorkingDir(dir)
                        .withCacheDir(cache);
                report = SessionContext.where(
                        session, () -> OutdatedPlans.compute(dir, cache, repoUrl == null ? null : URI.create(repoUrl)));
            } catch (Exception e) {
                report = OutdatedReport.error(Errors.text(e));
            }
            host.sendQuiet(writer, report.encode());

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
