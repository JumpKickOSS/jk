// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.io.BufferedWriter;
import java.nio.file.Path;

public final class ProvisionVerb implements HostedVerb {

    private final VerbHost host;

    public ProvisionVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.PROVISION_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("provision");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-provision-";
    }

    @Override
    public void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                var outcome = cc.jumpkick.runtime.CompatPlans.provision(
                        Path.of(Jsonl.str(requestLine, "cache")),
                        Path.of(Jsonl.str(requestLine, "dir")),
                        Path.of(Jsonl.str(requestLine, "toolsRoot")),
                        Jsonl.bool(requestLine, "noDiscover", false),
                        Jsonl.bool(requestLine, "gradle", false));
                host.sendQuiet(
                        writer,
                        EngineProtocol.provisionResult(
                                outcome.bin(),
                                outcome.version(),
                                outcome.source(),
                                outcome.error(),
                                outcome.exit(),
                                outcome.diag()));
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
            }

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
    }
}
