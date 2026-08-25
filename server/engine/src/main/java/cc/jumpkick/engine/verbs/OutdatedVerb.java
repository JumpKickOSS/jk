// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.OutdatedReport;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.runtime.OutdatedPlans;
import java.io.BufferedWriter;
import java.net.URI;
import java.nio.file.Path;

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
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            OutdatedReport report;
            try {
                Path dir = Path.of(Jsonl.str(requestLine, "dir"));
                Path cache = Path.of(Jsonl.str(requestLine, "cache"));
                String repoUrl = Jsonl.str(requestLine, "repoUrl");
                JkConfig config = JkConfig.empty()
                        .withOffline(Jsonl.bool(requestLine, "offline", false))
                        .withRebuild(Jsonl.bool(requestLine, "rebuild", false))
                        .withForce(Jsonl.bool(requestLine, "force", false));
                Session session = Session.defaults()
                        .withConfig(config)
                        .withWorkingDir(dir)
                        .withCacheDir(cache);
                report = SessionContext.where(
                        session, () -> OutdatedPlans.compute(dir, cache, repoUrl == null ? null : URI.create(repoUrl)));
            } catch (Exception e) {
                report = OutdatedReport.error(cc.jumpkick.host.Errors.text(e));
            }
            host.sendQuiet(writer, report.encode());

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
