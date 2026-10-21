// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.graph.GraphRunner;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.host.Errors;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.GraphAck;
import cc.jumpkick.wire.protocol.GraphRequest;
import java.io.BufferedWriter;
import org.jspecify.annotations.Nullable;

/** {@code jk graph}: refresh the workspace's code graph in the graph worker and answer one operation. */
public final class GraphVerb implements HostedVerb {

    private final VerbHost host;

    public GraphVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.GRAPH_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("graph");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-graph-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        GraphAck ack;
        try {
            ack = GraphRunner.run(GraphRequest.decode(requestLine), p -> host.sendQuiet(writer, p.encode()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ack = GraphAck.error("graph request interrupted");
        } catch (Exception e) {
            ack = GraphAck.error(Errors.text(e));
        }
        host.sendQuiet(writer, ack.encode());
        return JobOutcome.declined();
    }
}
