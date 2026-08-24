// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.GeneratedFiles;
import cc.jumpkick.engine.protocol.ProtoReads;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.runtime.GenerateOps;
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
    public @org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            GeneratedFiles files;
            try {
                files = GenerateOps.generate(
                        Path.of(Jsonl.str(requestLine, "dir")),
                        Jsonl.str(requestLine, "kind"),
                        ProtoReads.generateParams(requestLine));
            } catch (RuntimeException e) {
                files = GeneratedFiles.error(cc.jumpkick.host.Errors.text(e));
            }
            host.sendQuiet(writer, files.encode());

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return null;
    }
}
