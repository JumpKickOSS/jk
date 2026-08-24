// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.IdeWireModel;
import cc.jumpkick.engine.protocol.ProtoJobs;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.runtime.IdeOps;
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
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            IdeWireModel model;
            try {
                String jdksDir = Jsonl.str(requestLine, ProtoJobs.JDKS_DIR);
                model = IdeOps.ideModel(
                        Path.of(Jsonl.str(requestLine, "dir")),
                        Path.of(Jsonl.str(requestLine, "cache")),
                        jdksDir == null ? null : Path.of(jdksDir),
                        false);
            } catch (RuntimeException e) {
                model = IdeWireModel.error(cc.jumpkick.host.Errors.text(e));
            }
            host.sendQuiet(writer, model.encode());

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
