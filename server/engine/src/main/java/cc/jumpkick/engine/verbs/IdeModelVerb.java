// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.BufferedWriter;
import java.nio.file.Path;

public final class IdeModelVerb implements HostedVerb {

    private final VerbHost host;

    public IdeModelVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.IDE_MODEL_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("ide-model");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-ide-";
    }

    @Override
    public cc.jumpkick.engine.jobs.@org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            cc.jumpkick.engine.protocol.IdeWireModel model;
            try {
                String jdksDir = Jsonl.str(requestLine, "jdksDir");
                model = cc.jumpkick.runtime.IdeOps.ideModel(
                        Path.of(Jsonl.str(requestLine, "dir")),
                        Path.of(Jsonl.str(requestLine, "cache")),
                        jdksDir == null ? null : Path.of(jdksDir),
                        false);
            } catch (RuntimeException e) {
                model = cc.jumpkick.engine.protocol.IdeWireModel.error(String.valueOf(e.getMessage()));
            }
            host.sendQuiet(writer, model.encode());

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return null;
    }
}
