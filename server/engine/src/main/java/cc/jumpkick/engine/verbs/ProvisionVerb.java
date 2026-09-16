// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.runtime.base.CompatPlans;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoEvents;
import cc.jumpkick.wire.protocol.ProvisionRequest;
import java.io.BufferedWriter;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

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
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            try {
                // `tool` present means an explicit `jk tool install <tool>[:<version>]`; absent
                // means the historical "read this project's wrapper" form.
                ProvisionRequest body = ProvisionRequest.decode(requestLine);
                Path toolsRoot = Path.of(body.toolsRoot());
                var outcome = body.tool() != null && !body.tool().isBlank()
                        ? CompatPlans.provisionTool(body.tool(), body.version(), toolsRoot, body.noDiscover())
                        : CompatPlans.provision(
                                Path.of(body.dir()),
                                toolsRoot,
                                body.noDiscover(),
                                body.acceptUnverified(),
                                body.gradle());
                host.sendQuiet(
                        writer,
                        ProtoEvents.provisionResult(
                                outcome.bin(),
                                outcome.version(),
                                outcome.source(),
                                outcome.verification(),
                                outcome.error(),
                                outcome.exit()));
                return outcome.exit() == Exit.SUCCESS ? JobOutcome.ok() : JobOutcome.failed(outcome.exit());
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
                return JobOutcome.failed(Exit.FAILURE);
            }
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
            return JobOutcome.failed(Exit.FAILURE);
        }
    }
}
