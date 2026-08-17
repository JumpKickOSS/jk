// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.PluginInstallLocalAck;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.runtime.PluginInstallLocalOps;
import java.io.BufferedWriter;
import java.nio.file.Path;

public final class PluginInstallLocalVerb implements HostedVerb {

    private final VerbHost host;

    public PluginInstallLocalVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.PLUGIN_INSTALL_LOCAL_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("plugin-install-local");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-plugin-il-";
    }

    @Override
    public cc.jumpkick.engine.jobs.@org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String cacheStr = Jsonl.str(requestLine, "cache");
            Path cache = cacheStr == null || cacheStr.isBlank() ? cc.jumpkick.util.JkDirs.cache() : Path.of(cacheStr);
            String installStr = Jsonl.str(requestLine, "installRoot");
            Path installRoot = installStr == null || installStr.isBlank() ? null : Path.of(installStr);
            PluginInstallLocalAck ack = PluginInstallLocalOps.run(
                    Path.of(Jsonl.str(requestLine, "dir")),
                    cache,
                    installRoot,
                    Jsonl.str(requestLine, "modules"),
                    Jsonl.bool(requestLine, "dryRun", false),
                    Jsonl.bool(requestLine, "ambientStore", true));
            host.sendQuiet(writer, ack.encode());
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return null;
    }
}
