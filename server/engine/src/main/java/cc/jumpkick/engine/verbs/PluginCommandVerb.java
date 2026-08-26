// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.PluginCommandReport;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.host.Errors;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.runtime.PluginCommands;
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
                // Under the request's session — see ExecPlanVerb (JK-1040). A plugin command that
                // forks a JVM must fork the one the caller selected, not the daemon's.
                Session session = host.resolveSession(requestLine, cancelToken, false);
                report = SessionContext.where(
                        session,
                        () -> PluginCommands.run(
                                Path.of(Jsonl.str(requestLine, "dir")),
                                Path.of(Jsonl.str(requestLine, "cache")),
                                Jsonl.str(requestLine, "command"),
                                Jsonl.strArray(requestLine, "args"),
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
