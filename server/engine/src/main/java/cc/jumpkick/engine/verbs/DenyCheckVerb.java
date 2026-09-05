// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.host.Errors;
import cc.jumpkick.runtime.PolicyOps;
import cc.jumpkick.wire.protocol.DenyCheckRequest;
import cc.jumpkick.wire.protocol.DenyReport;
import cc.jumpkick.wire.protocol.EngineProtocol;
import java.io.BufferedWriter;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

public final class DenyCheckVerb implements HostedVerb {

    private final VerbHost host;

    public DenyCheckVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.DENY_CHECK_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("deny-check");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.SyncRead();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-deny-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
        try {
            DenyReport report;
            try {
                report = PolicyOps.denyCheck(
                        Path.of(DenyCheckRequest.decode(requestLine).dir()));
            } catch (RuntimeException e) {
                report = DenyReport.error(Errors.text(e));
            }
            host.sendQuiet(writer, report.encode());

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
        return JobOutcome.declined();
    }
}
