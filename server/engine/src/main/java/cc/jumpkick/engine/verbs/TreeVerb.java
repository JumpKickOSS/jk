// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.host.Errors;
import cc.jumpkick.runtime.GraphOps;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoReads;
import cc.jumpkick.wire.protocol.TreeRequest;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;

public final class TreeVerb implements HostedVerb {

    private final VerbHost host;

    public TreeVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.TREE_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("tree");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-tree-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            String error = null;
            String rendered = null;
            try {
                TreeRequest req = TreeRequest.decode(requestLine);
                rendered = GraphOps.treeRender(
                        Path.of(req.dir()), req.maxDepth(), req.flatten(), req.stack(), req.scopes());
            } catch (IOException | RuntimeException e) {
                error = Errors.text(e);
            }
            host.sendQuiet(writer, ProtoReads.treeAck(error, rendered));

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
