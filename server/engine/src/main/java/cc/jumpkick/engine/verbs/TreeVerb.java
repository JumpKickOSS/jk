// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoReads;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.runtime.GraphOps;
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
                rendered = GraphOps.treeRender(
                        Path.of(Jsonl.str(requestLine, "dir")),
                        Jsonl.intValue(requestLine, "maxDepth", Integer.MAX_VALUE),
                        Jsonl.bool(requestLine, "flatten", false),
                        Jsonl.bool(requestLine, "stack", false),
                        Jsonl.strArray(requestLine, "scopes"));
            } catch (IOException | RuntimeException e) {
                error = cc.jumpkick.host.Errors.text(e);
            }
            host.sendQuiet(writer, ProtoReads.treeAck(error, rendered));

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
