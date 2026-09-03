// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.host.Errors;
import cc.jumpkick.runtime.PluginCommands;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.PluginCommandReport;
import cc.jumpkick.wire.protocol.PluginCommandRequest;
import cc.jumpkick.wire.protocol.ProtoSession;
import java.io.BufferedWriter;
import java.nio.file.Path;

public final class PluginCommandVerb implements HostedVerb {

    private final VerbHost host;

    public PluginCommandVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.PLUGIN_VERB_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("plugin");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-plugin-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            PluginCommandReport report;
            try {
                // Under the request's session — see ExecPlanVerb. A plugin command that
                // forks a JVM must fork the one the caller selected, not the daemon's.
                Session session = host.resolveSession(requestLine, cancelToken, false);
                PluginCommandRequest req = PluginCommandRequest.decode(requestLine);
                report = SessionContext.where(
                        session,
                        () -> PluginCommands.run(
                                Path.of(req.dir()),
                                Path.of(req.cache()),
                                req.command(),
                                req.args(),
                                ProtoSession.variantOf(requestLine),
                                ProtoSession.clientEnvOf(requestLine)));
            } catch (Exception e) {
                report = PluginCommandReport.error(Errors.text(e));
            }
            host.sendQuiet(writer, report.encode());

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
