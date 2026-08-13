// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.plugin.protocol.Jsonl;
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
    public void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            cc.jumpkick.engine.protocol.PluginCommandReport report;
            try {
                report = cc.jumpkick.runtime.PluginCommands.run(
                        Path.of(Jsonl.str(requestLine, "dir")),
                        Path.of(Jsonl.str(requestLine, "cache")),
                        Jsonl.str(requestLine, "command"),
                        Jsonl.strArray(requestLine, "args"),
                        EngineProtocol.variantOf(requestLine),
                        EngineProtocol.clientEnvOf(requestLine));
            } catch (RuntimeException e) {
                report = cc.jumpkick.engine.protocol.PluginCommandReport.error(String.valueOf(e.getMessage()));
            }
            host.sendQuiet(writer, report.encode());

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
    }
}
