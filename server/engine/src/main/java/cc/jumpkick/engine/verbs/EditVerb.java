// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoReads;
import cc.jumpkick.host.Errors;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.runtime.EditOps;
import java.io.BufferedWriter;
import java.nio.file.Path;

public final class EditVerb implements HostedVerb {

    private final VerbHost host;

    public EditVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.EDIT_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("edit");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-edit-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            EditOps.Result result;
            try {
                result = EditOps.apply(
                        Path.of(Jsonl.str(requestLine, "file")),
                        Jsonl.str(requestLine, "op"),
                        Jsonl.strArray(requestLine, "args"));
            } catch (RuntimeException e) {
                result = new EditOps.Result(false, Errors.text(e));
            }
            host.sendQuiet(writer, ProtoReads.editAck(result.changed(), result.error(), result.detail()));

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
