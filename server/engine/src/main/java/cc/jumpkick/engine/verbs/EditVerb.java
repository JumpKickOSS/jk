// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.host.Errors;
import cc.jumpkick.runtime.base.EditOps;
import cc.jumpkick.wire.protocol.EditRequest;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoReads;
import java.io.BufferedWriter;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

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
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            EditOps.Result result;
            try {
                EditRequest req = EditRequest.decode(requestLine);
                result = EditOps.apply(Path.of(req.file()), req.op(), req.args());
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
