// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ModuleGraphAck;
import cc.jumpkick.engine.runtime.ModuleGraphOps;
import cc.jumpkick.jsonl.Jsonl;
import java.io.BufferedWriter;
import java.nio.file.Path;

public final class ModuleGraphVerb implements HostedVerb {

    private final VerbHost host;

    public ModuleGraphVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.MODULE_GRAPH_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("module-graph");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-modgraph-";
    }

    @Override
    public cc.jumpkick.engine.jobs.@org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            ModuleGraphAck ack;
            try {
                String dir = Jsonl.str(requestLine, "dir");
                if (dir == null || dir.isBlank()) {
                    // A resident server has no meaningful cwd to fall back to (JK-2166).
                    throw new IllegalArgumentException("module-graph request names no dir");
                }
                ack = ModuleGraphOps.render(
                        Path.of(dir),
                        Jsonl.str(requestLine, "format"),
                        Jsonl.str(requestLine, "modules"),
                        Jsonl.str(requestLine, "affectedSince"));
            } catch (Exception e) {
                ack = ModuleGraphAck.error(cc.jumpkick.host.Errors.text(e));
            }
            host.sendQuiet(writer, ack.encode());
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return null;
    }
}
