// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoLifecycle;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.BufferedWriter;

public final class CalibrateVerb implements HostedVerb {

    private final VerbHost host;

    public CalibrateVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.CALIBRATE_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("calibrate");
    }

    @Override
    public VerbShape shape() {
        // A forced calibrate runs real micro-benchmarks for seconds: connection-owned job
        // (cancellable, joins active plans) rather than an inline read with a throwaway token.
        // Its journal row is synthetic (trigger "calibrate") and is deleted at write.
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-calibrate-";
    }

    @Override
    public cc.jumpkick.engine.jobs.@org.jspecify.annotations.Nullable JobOutcome run(
            String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                boolean force = Jsonl.bool(requestLine, "force", false);
                boolean allowNetwork = Jsonl.bool(requestLine, "allowNetwork", true);
                long cold = Jsonl.longValue(requestLine, "engineColdStartMs", 0);
                cc.jumpkick.runtime.Calibration cal = cc.jumpkick.runtime.Calibration.ensure(null, force, allowNetwork);
                if (cold > 0) {
                    cal = cc.jumpkick.runtime.Calibration.recordEngineColdStart(cold, System.currentTimeMillis());
                }
                host.sendQuiet(
                        writer,
                        ProtoLifecycle.calibrateAck(
                                cal.present(),
                                cal.msPerWeight(),
                                cal.jvmForkMs(),
                                cal.javacMs(),
                                cal.diskIoMs(),
                                cal.hashCpuMs(),
                                cal.junitForkMs(),
                                cal.junitRunMs(),
                                cal.junitPlatformMs(),
                                cal.resolveMs(),
                                cal.engineColdStartMs(),
                                cal.measured(),
                                cal.junitPlatformUsed(),
                                cal.resolveUsed(),
                                cal.summary()));
            } catch (Exception e) {
                host.sendQuiet(
                        writer,
                        ProtoLifecycle.calibrateAck(
                                false,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                false,
                                false,
                                false,
                                "calibration failed: " + e.getMessage()));
            }

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return null;
    }
}
