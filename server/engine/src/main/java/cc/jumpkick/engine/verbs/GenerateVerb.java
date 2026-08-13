// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoReads;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.BufferedWriter;
import java.nio.file.Path;

public final class GenerateVerb implements HostedVerb {

    private final VerbHost host;

    public GenerateVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.GENERATE_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("generate");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-generate-";
    }

    @Override
    public void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            cc.jumpkick.engine.protocol.GeneratedFiles files;
            try {
                files = cc.jumpkick.runtime.GenerateOps.generate(
                        Path.of(Jsonl.str(requestLine, "dir")),
                        Jsonl.str(requestLine, "kind"),
                        ProtoReads.generateParams(requestLine));
            } catch (RuntimeException e) {
                files = cc.jumpkick.engine.protocol.GeneratedFiles.error(String.valueOf(e.getMessage()));
            }
            host.sendQuiet(writer, files.encode());

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
    }
}
