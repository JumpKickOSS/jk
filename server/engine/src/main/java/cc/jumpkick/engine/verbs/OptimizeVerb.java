// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoLifecycle;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.BufferedWriter;

public final class OptimizeVerb implements HostedVerb {

    private final VerbHost host;

    public OptimizeVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.OPTIMIZE_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("optimize");
    }

    @Override
    public VerbShape shape() {
        // Schedules host warmup on the idle worker and acks immediately — genuinely a sync read.
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-optimize-";
    }

    @Override
    public cc.jumpkick.engine.jobs.@org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                boolean force = Jsonl.bool(requestLine, "force", false);
                boolean scheduled = host.scheduleHostWarmup(force);
                host.sendQuiet(
                        writer,
                        scheduled
                                ? ProtoLifecycle.optimizeAck(
                                        true, "", "scheduled", "scheduled: host warmup on idle worker")
                                : ProtoLifecycle.optimizeAck(
                                        true, "", "", "nothing to do: worker AOT and calibration are current"));
            } catch (RuntimeException e) {
                host.sendQuiet(writer, ProtoLifecycle.optimizeAck(false, "", "", "optimize failed: " + e.getMessage()));
            }

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return null;
    }
}
